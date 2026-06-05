package com.simon.campus.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.mapper.ChildChunkMapper;
import com.simon.campus.model.dto.RecallCandidate;
import com.simon.campus.model.entity.ChildChunk;
import com.simon.campus.service.ingest.BM25Indexer;
import com.simon.campus.service.ingest.VisibilityPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * BM25 稀疏检索器：基于 BM25Indexer 的倒排索引实现关键词稀疏召回，从数据库中加载匹配子块内容
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BM25Retriever {

    private final BM25Indexer bm25Indexer; // BM25 索引器
    private final ChildChunkMapper childChunkMapper; // 子块数据库 Mapper

    /**
     * BM25 检索：分词搜索 → 加载子块内容 → 可见性过滤 → 构建候选列表
     */
    public List<RecallCandidate> retrieve(String query, int userAccessLevel, int topK) {
        try {
            List<Map.Entry<String, Double>> scored = bm25Indexer.search(query, userAccessLevel, topK); // BM25 分词搜索并评分
            if (scored.isEmpty()) return Collections.emptyList(); // 无匹配结果则返回空列表

            List<String> childIds = scored.stream().map(Map.Entry::getKey).toList(); // 提取子块 ID 列表
            Map<String, Double> scoreMap = new HashMap<>(); // 子块 ID → BM25 分数映射
            scored.forEach(e -> scoreMap.put(e.getKey(), e.getValue())); // 填充分数映射

            Map<String, ChildChunk> chunkMap = new HashMap<>(); // 子块 ID → 实体映射
            LambdaQueryWrapper<ChildChunk> qw = new LambdaQueryWrapper<ChildChunk>()
                .in(ChildChunk::getChildId, childIds); // 按 ID 列表批量查询
            childChunkMapper.selectList(qw)
                .forEach(c -> chunkMap.put(c.getChildId(), c)); // 填充映射

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
                    .source("bm25")
                    .build());
            }
            return candidates; // 返回过滤后的候选列表
        } catch (Exception e) {
            log.warn("BM25Retriever failed: {}", e.getMessage()); // 检索失败时记录警告
            return Collections.emptyList(); // 返回空列表
        }
    }
}
