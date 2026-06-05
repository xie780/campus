package com.simon.campus.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.campus.mapper.FaqPairMapper;
import com.simon.campus.model.dto.RecallCandidate;
import com.simon.campus.model.entity.FaqPair;
import com.simon.campus.service.admin.SystemConfigService;
import com.simon.campus.service.ingest.EmbeddingService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * FAQ 匹配器：基于缓存的向量余弦相似度匹配常见问题，
 * 分数 ≥ 0.92 直接短路返回答案，0.85–0.92 加入 RRF 候选列表
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaqMatcher {

    private static final String CACHE_KEY = "faq:vectors"; // Redis 缓存键
    private final FaqPairMapper faqPairMapper; // FAQ 数据库 Mapper
    private final EmbeddingService embeddingService; // 文本嵌入服务
    private final StringRedisTemplate redisTemplate; // Redis 模板
    private final ObjectMapper objectMapper; // JSON 序列化工具
    private final SystemConfigService configService; // 系统配置服务

    /**
     * FAQ 匹配结果：是否短路、直接答案、低置信度候选列表
     */
    public record FaqMatchResult(
        boolean shortCircuited,
        String directAnswer,
        List<RecallCandidate> candidates
    ) {}

    /**
     * 可序列化的 FAQ 条目，用于 Redis 缓存
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FaqEntry {
        private Long id; // FAQ ID
        private String question; // 问题文本
        private String answer; // 答案文本
        private float[] vector; // 问题嵌入向量
    }

    /**
     * 匹配 FAQ：将查询嵌入向量与缓存中的 FAQ 向量做余弦相似度比较
     */
    public FaqMatchResult match(String query) {
        try {
            float[] queryVec = embeddingService.embedOne(query); // 将查询文本转为嵌入向量
            List<FaqEntry> entries = loadFaqEntries(); // 加载 FAQ 条目（优先从缓存）
            if (entries.isEmpty()) return new FaqMatchResult(false, null, Collections.emptyList()); // 无 FAQ 条目则返回空

            double bestScore = -1; // 最高匹配分数
            FaqEntry bestEntry = null; // 最佳匹配条目
            List<RecallCandidate> candidates = new ArrayList<>(); // 低置信度候选列表
            double exactThreshold = configService.getDouble("faq.match.exact_thresh", 0.92); // 精确匹配阈值
            double candidateThreshold = configService.getDouble("faq.match.candidate_thresh", 0.85); // 候选匹配阈值

            for (FaqEntry e : entries) { // 遍历所有 FAQ 条目
                double score = cosine(queryVec, e.getVector()); // 计算余弦相似度
                if (score >= exactThreshold) { // 达到精确匹配阈值
                    if (score > bestScore) { // 更新最佳匹配
                        bestScore = score;
                        bestEntry = e;
                    }
                } else if (score >= candidateThreshold) { // 达到候选匹配阈值
                    candidates.add(RecallCandidate.builder() // 构建召回候选
                        .childId("faq_" + e.getId())
                        .parentId("faq_" + e.getId())
                        .docId("faq")
                        .docTitle("常见问题")
                        .headingPath(e.getQuestion())
                        .content(e.getQuestion() + "\n" + e.getAnswer())
                        .pageStart(null)
                        .score(score)
                        .source("faq")
                        .build());
                }
            }

            if (bestEntry != null) { // 存在精确匹配
                incrementHitCount(bestEntry.getId()); // 递增命中计数
                return new FaqMatchResult(true, bestEntry.getAnswer(), Collections.emptyList()); // 返回短路结果
            }
            return new FaqMatchResult(false, null, candidates); // 返回候选列表
        } catch (Exception e) {
            log.warn("FaqMatcher failed: {}", e.getMessage()); // 匹配失败时记录警告
            return new FaqMatchResult(false, null, Collections.emptyList()); // 返回空结果
        }
    }

    /**
     * 加载 FAQ 条目：优先从 Redis 缓存读取，缓存未命中则从数据库重建
     */
    private List<FaqEntry> loadFaqEntries() {
        String cached = redisTemplate.opsForValue().get(CACHE_KEY); // 从 Redis 读取缓存
        if (cached != null) { // 缓存命中
            try {
                FaqEntry[] arr = objectMapper.readValue(cached, FaqEntry[].class); // 反序列化
                return Arrays.asList(arr); // 返回缓存结果
            } catch (Exception e) {
                log.warn("FAQ cache parse failed, reloading from DB"); // 缓存解析失败
            }
        }
        return rebuildCache(); // 从数据库重建缓存
    }

    /**
     * 重建 FAQ 缓存：从数据库加载启用的 FAQ 条目，计算嵌入向量，写入 Redis
     */
    public List<FaqEntry> rebuildCache() {
        List<FaqPair> pairs = faqPairMapper.findAllEnabled(); // 查询所有启用的 FAQ
        List<FaqEntry> entries = new ArrayList<>(); // FAQ 条目列表
        for (FaqPair p : pairs) {
            if (p.getEmbeddingJson() == null || p.getEmbeddingJson().isBlank()) continue; // 跳过无嵌入的条目
            try {
                float[] vec = objectMapper.readValue(p.getEmbeddingJson(), float[].class); // 反序列化嵌入向量
                entries.add(new FaqEntry(p.getId(), p.getQuestion(), p.getAnswer(), vec)); // 构建条目
            } catch (Exception e) {
                log.debug("Skip FAQ {} - bad embedding: {}", p.getId(), e.getMessage()); // 跳过嵌入异常的条目
            }
        }
        try {
            redisTemplate.opsForValue().set(CACHE_KEY, // 写入 Redis 缓存
                objectMapper.writeValueAsString(entries), Duration.ofHours(1)); // 缓存 1 小时
        } catch (Exception e) {
            log.warn("Failed to cache FAQ entries: {}", e.getMessage()); // 缓存写入失败
        }
        return entries; // 返回条目列表
    }

    /**
     * 计算两个向量的余弦相似度
     */
    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0; // 空向量或长度不匹配返回 0
        double dot = 0, normA = 0, normB = 0; // 点积和范数
        for (int i = 0; i < a.length; i++) { // 逐元素计算
            dot += a[i] * b[i]; // 点积累加
            normA += a[i] * a[i]; // A 范数累加
            normB += b[i] * b[i]; // B 范数累加
        }
        if (normA == 0 || normB == 0) return 0; // 零向量返回 0
        return dot / (Math.sqrt(normA) * Math.sqrt(normB)); // 余弦相似度
    }

    /**
     * 递增 FAQ 条目的命中计数
     */
    private void incrementHitCount(Long faqId) {
        try {
            faqPairMapper.update(null, // 更新命中计数
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FaqPair>()
                    .eq(FaqPair::getId, faqId) // 按 ID 匹配
                    .setSql("hit_count = hit_count + 1")); // 命中计数 +1
        } catch (Exception e) {
            log.debug("Failed to increment FAQ hit_count: {}", e.getMessage()); // 更新失败时记录
        }
    }
}
