package com.simon.campus.service.ingest;

import com.simon.campus.model.entity.ChildChunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.InsertParam.Field;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量数据库服务：提供子块向量的批量插入、相似度搜索和按文档删除
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MilvusService {

    private final MilvusServiceClient milvusServiceClient; // Milvus 客户端

    @Value("${milvus.collection-name}")
    private String collectionName; // 集合名称

    private static final List<String> SEARCH_OUTPUT_FIELDS = // 搜索返回字段
        Arrays.asList("child_id", "parent_id", "doc_id", "doc_title", "category", "access_level", "page_start");

    /**
     * 批量插入子块向量到 Milvus
     */
    public void insertBatch(List<ChildChunk> chunks, List<float[]> embeddings) {
        if (chunks.isEmpty()) return; // 空列表跳过

        List<String> childIds   = new ArrayList<>(); // 子块 ID 列表
        List<String> parentIds  = new ArrayList<>(); // 父块 ID 列表
        List<String> docIds     = new ArrayList<>(); // 文档 ID 列表
        List<String> docTitles  = new ArrayList<>(); // 文档标题列表
        List<String> categories = new ArrayList<>(); // 分类列表
        List<Integer> accessLevels = new ArrayList<>(); // 访问级别列表
        List<Integer> pageStarts   = new ArrayList<>(); // 起始页列表
        List<List<Float>> vectors  = new ArrayList<>(); // 向量列表

        for (int i = 0; i < chunks.size(); i++) { // 遍历子块
            ChildChunk c = chunks.get(i);
            childIds.add(c.getChildId()); // 子块 ID
            parentIds.add(c.getParentId()); // 父块 ID
            docIds.add(c.getDocId()); // 文档 ID
            docTitles.add(truncate(c.getDocTitle(), 255)); // 文档标题（截断）
            categories.add(c.getCategory() != null ? c.getCategory() : ""); // 分类
            accessLevels.add(c.getAccessLevel()); // 访问级别
            pageStarts.add(c.getPageStart() != null ? c.getPageStart() : 0); // 起始页
            vectors.add(toFloatList(embeddings.get(i))); // 向量
        }

        List<Field> fields = Arrays.asList( // 构建插入字段
            new Field("child_id",     childIds),
            new Field("parent_id",    parentIds),
            new Field("doc_id",       docIds),
            new Field("doc_title",    docTitles),
            new Field("category",     categories),
            new Field("access_level", accessLevels),
            new Field("page_start",   pageStarts),
            new Field("embedding",    vectors)
        );

        InsertParam insertParam = InsertParam.newBuilder() // 构建插入参数
            .withCollectionName(collectionName)
            .withFields(fields)
            .build();

        R<MutationResult> result = milvusServiceClient.insert(insertParam); // 执行插入
        if (result.getStatus() != R.Status.Success.getCode()) { // 插入失败
            throw new RuntimeException("Milvus insert failed: " + result.getMessage());
        }
        log.debug("Inserted {} vectors into Milvus", chunks.size()); // 记录插入数量
    }

    /**
     * 向量相似度搜索（带可见性过滤）
     */
    public List<SearchResultsWrapper.IDScore> search(float[] queryVector, int userAccessLevel, int topK) {
        String filter = buildAccessFilter(userAccessLevel); // 构建访问级别过滤表达式

        SearchParam searchParam = SearchParam.newBuilder() // 构建搜索参数
            .withCollectionName(collectionName)
            .withMetricType(io.milvus.param.MetricType.IP) // 内积相似度
            .withOutFields(SEARCH_OUTPUT_FIELDS) // 返回字段
            .withTopK(topK) // 返回数量
            .withVectors(Collections.singletonList(toFloatList(queryVector))) // 查询向量
            .withVectorFieldName("embedding") // 向量字段名
            .withExpr(filter) // 过滤表达式
            .withParams("{\"ef\":128}") // HNSW 搜索参数
            .build();

        R<SearchResults> result = milvusServiceClient.search(searchParam); // 执行搜索
        if (result.getStatus() != R.Status.Success.getCode()) { // 搜索失败
            log.error("Milvus search failed: {}", result.getMessage());
            return Collections.emptyList(); // 返回空列表
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults()); // 解析结果
        return wrapper.getIDScore(0); // 返回第一组结果
    }

    /**
     * 按文档 ID 删除所有相关向量
     */
    public void deleteByDocId(String docId) {
        DeleteParam deleteParam = DeleteParam.newBuilder() // 构建删除参数
            .withCollectionName(collectionName)
            .withExpr("doc_id == \"" + docId + "\"") // 按文档 ID 过滤
            .build();
        R<MutationResult> result = milvusServiceClient.delete(deleteParam); // 执行删除
        if (result.getStatus() != R.Status.Success.getCode()) { // 删除失败
            throw new RuntimeException("Milvus delete failed: " + result.getMessage());
        }
        log.info("[DELETE_FLOW] step=milvus_delete collection={} docId={}", collectionName, docId); // 记录删除日志
    }

    /**
     * 构建基于访问级别的过滤表达式
     */
    private String buildAccessFilter(int userAccessLevel) {
        return "access_level in " + VisibilityPolicy.visibleLevelsForViewer(userAccessLevel); // 可见级别列表
    }

    /**
     * 将 float[] 转为 List<Float>
     */
    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f); // 逐个转换
        return list;
    }

    /**
     * 截断字符串到指定长度
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return ""; // 空值返回空串
        return s.length() > maxLen ? s.substring(0, maxLen) : s; // 超长截断
    }
}
