package com.simon.campus.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.mapper.ChildChunkMapper;
import com.simon.campus.model.dto.RecallCandidate;
import com.simon.campus.model.entity.ChildChunk;
import com.simon.campus.service.ingest.EmbeddingService;
import com.simon.campus.service.ingest.MilvusService;
import com.simon.campus.service.ingest.VisibilityPolicy;
import io.milvus.response.SearchResultsWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 稠密检索器：基于向量嵌入与 Milvus 相似度搜索实现稠密召回，从向量数据库中检索与查询语义最接近的子块
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DenseRetriever {

    private final MilvusService milvusService; // Milvus 向量搜索服务
    private final EmbeddingService embeddingService; // 文本嵌入服务
    private final ChildChunkMapper childChunkMapper; // 子块数据库 Mapper

    /**
     * 稠密检索：将查询文本嵌入为向量 → Milvus 相似度搜索 → 从数据库加载子块内容
     */
    public List<RecallCandidate> retrieve(String query, int userAccessLevel, int topK) {
        try {
            float[] vector = embeddingService.embedOne(query); // 将查询文本转为嵌入向量
            List<SearchResultsWrapper.IDScore> results = milvusService.search(vector, userAccessLevel, topK); // Milvus 向量相似度搜索
            return buildCandidates(results, "dense", userAccessLevel); // 构建召回候选列表
        } catch (Exception e) {
            log.warn("DenseRetriever failed: {}", e.getMessage()); // 检索失败时记录警告
            return Collections.emptyList(); // 返回空列表
        }
    }

    /**
     * 将 Milvus 搜索结果转换为召回候选列表，并做可见性过滤
     */
    private List<RecallCandidate> buildCandidates(
            List<SearchResultsWrapper.IDScore> results, String source, int userAccessLevel) {
        if (results.isEmpty()) return Collections.emptyList(); // 无搜索结果则直接返回空列表

        List<String> childIds = new ArrayList<>(); // 子块 ID 列表
        Map<String, Double> scoreMap = new LinkedHashMap<>(); // 子块 ID → 相似度分数映射
        for (SearchResultsWrapper.IDScore r : results) {
            // Milvus 主键类型兼容：VarChar 用 getStrID()，Int64 用 getLongID()
            String childId;
            try {
                childId = r.getStrID(); // 尝试以字符串主键获取
            } catch (Exception e) {
                childId = String.valueOf(r.getLongID()); // 回退到长整型主键
            }
            if (childId == null || childId.isBlank()) continue; // 跳过空主键
            childIds.add(childId); // 收集有效子块 ID
            scoreMap.put(childId, (double) r.getScore()); // 记录相似度分数
        }

        // 批量从 MySQL 加载子块内容
        Map<String, ChildChunk> chunkMap = new HashMap<>(); // 子块 ID → 实体映射
        if (!childIds.isEmpty()) {
            LambdaQueryWrapper<ChildChunk> qw = new LambdaQueryWrapper<ChildChunk>()
                .in(ChildChunk::getChildId, childIds); // 按 ID 列表批量查询
            childChunkMapper.selectList(qw)
                .forEach(c -> chunkMap.put(c.getChildId(), c)); // 填充映射
        }

        List<RecallCandidate> candidates = new ArrayList<>(); // 最终候选列表
        for (String childId : childIds) {
            ChildChunk c = chunkMap.get(childId); // 获取子块实体
            if (c == null) continue; // 跳过数据库中不存在的子块
            if (!VisibilityPolicy.canView(c.getAccessLevel(), userAccessLevel)) continue; // 可见性过滤
            candidates.add(RecallCandidate.builder() // 构建召回候选对象
                .childId(childId)
                .parentId(c.getParentId())
                .docId(c.getDocId())
                .docTitle(c.getDocTitle())
                .headingPath(c.getHeadingPath())
                .content(c.getContent())
                .pageStart(c.getPageStart())
                .score(scoreMap.getOrDefault(childId, 0.0))
                .source(source)
                .build());
        }
        return candidates; // 返回过滤后的候选列表
    }
}
