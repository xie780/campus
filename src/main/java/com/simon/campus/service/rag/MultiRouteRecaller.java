package com.simon.campus.service.rag;

import com.simon.campus.model.dto.QueryExpansion;
import com.simon.campus.model.dto.RecallCandidate;
import com.simon.campus.service.admin.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多路召回器（RAG Stage 3）：并行执行稠密检索、BM25 稀疏检索和 FAQ 匹配三路召回，
 * 通过 RRF（Reciprocal Rank Fusion）融合各路结果，输出统一的候选列表
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MultiRouteRecaller {

    private static final int RRF_K = 60; // RRF 融合参数 k，控制排名衰减速度
    private static final int RRF_OUTPUT_TOP_K = 20; // RRF 融合后输出的最大候选数

    private final DenseRetriever denseRetriever; // 稠密检索器
    private final BM25Retriever bm25Retriever; // BM25 稀疏检索器
    private final FaqMatcher faqMatcher; // FAQ 匹配器
    private final SystemConfigService configService; // 系统配置服务

    private final ExecutorService executor = Executors.newFixedThreadPool(3); // 3 线程并行召回

    /**
     * 多路召回结果：是否 FAQ 短路、FAQ 直接答案、融合后的候选列表
     */
    public record RecallResult(
        boolean faqShortCircuited,
        String faqAnswer,
        List<RecallCandidate> candidates
    ) {}

    /**
     * 执行多路召回：先 FAQ 快速匹配，再并行执行稠密 + BM25 检索，最后 RRF 融合
     */
    public RecallResult recall(QueryExpansion expansion, int userAccessLevel) {
        String mainQuery = expansion.getMainQuery(); // 获取主查询
        int mainTopK = configService.getInt("rag.topk.child", 20); // 主查询 topK
        int subTopK = configService.getInt("rag.topk.child_sub", 7); // 子查询 topK

        // 优先 FAQ 快速匹配（短路路径）
        FaqMatcher.FaqMatchResult faqResult = faqMatcher.match(mainQuery); // FAQ 匹配
        log.info("[AGENT_FLOW] step=faq_match query={} shortCircuited={} candidates={}",
            abbreviate(mainQuery), faqResult.shortCircuited(), faqResult.candidates().size()); // 记录 FAQ 匹配流程
        if (faqResult.shortCircuited()) { // FAQ 高置信度匹配，直接返回答案
            log.debug("FAQ short-circuit matched for: {}", mainQuery); // 记录短路匹配
            return new RecallResult(true, faqResult.directAnswer(), Collections.emptyList()); // 返回 FAQ 短路结果
        }

        // 并行执行：稠密检索（主查询 + 子查询）+ BM25 检索 + FAQ 候选
        CompletableFuture<List<RecallCandidate>> denseFuture = CompletableFuture.supplyAsync(() -> {
            List<RecallCandidate> all = new ArrayList<>(
                denseRetriever.retrieve(mainQuery, userAccessLevel, mainTopK)); // 主查询稠密检索
            for (String sub : expansion.getSubQueries()) {
                all.addAll(denseRetriever.retrieve(sub, userAccessLevel, subTopK)); // 子查询稠密检索
            }
            return all;
        }, executor);

        CompletableFuture<List<RecallCandidate>> bm25Future = CompletableFuture.supplyAsync(() ->
            bm25Retriever.retrieve(mainQuery, userAccessLevel, mainTopK), executor); // BM25 稀疏检索

        List<RecallCandidate> faqCandidates = faqResult.candidates(); // FAQ 低置信度候选

        List<RecallCandidate> denseResults; // 稠密检索结果
        List<RecallCandidate> bm25Results; // BM25 检索结果
        try {
            denseResults = denseFuture.get(); // 等待稠密检索完成
            bm25Results = bm25Future.get(); // 等待 BM25 检索完成
        } catch (Exception e) {
            log.error("Recall futures failed: {}", e.getMessage()); // 并行召回失败
            denseResults = Collections.emptyList(); // 降级为空结果
            bm25Results = Collections.emptyList(); // 降级为空结果
        }

        // 去重并构建各路排序列表
        List<RecallCandidate> denseDeduped = dedup(denseResults); // 稠密结果去重
        List<RecallCandidate> bm25Deduped  = dedup(bm25Results); // BM25 结果去重

        List<RecallCandidate> merged = rrfMerge(denseDeduped, bm25Deduped, faqCandidates); // RRF 融合
        log.info("[AGENT_FLOW] step=multi_recall dense={} bm25={} faqCandidates={} rrfOut={} hits={}",
            denseDeduped.size(), bm25Deduped.size(), faqCandidates.size(), merged.size(), summarizeHits(merged)); // 记录多路召回流程

        return new RecallResult(false, null, merged); // 返回融合结果
    }

    /**
     * RRF 融合：每个列表中的候选按排名贡献 1/(k + rank) 分数，合并后按总分降序排列
     */
    @SafeVarargs
    private List<RecallCandidate> rrfMerge(List<RecallCandidate>... lists) {
        Map<String, Double> rrfScores = new LinkedHashMap<>(); // 子块 ID → RRF 累计分数
        Map<String, RecallCandidate> candidates = new LinkedHashMap<>(); // 子块 ID → 候选对象

        for (List<RecallCandidate> list : lists) { // 遍历每路召回结果
            for (int rank = 0; rank < list.size(); rank++) { // 按排名遍历
                RecallCandidate c = list.get(rank); // 获取候选
                double contrib = 1.0 / (RRF_K + rank + 1); // 计算排名贡献分数
                rrfScores.merge(c.getChildId(), contrib, Double::sum); // 累加 RRF 分数
                candidates.putIfAbsent(c.getChildId(), c); // 保留首次出现的候选对象
            }
        }

        return rrfScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed()) // 按分数降序排列
            .limit(RRF_OUTPUT_TOP_K) // 取 topK
            .map(e -> {
                RecallCandidate c = candidates.get(e.getKey()); // 获取候选对象
                c.setScore(e.getValue()); // 设置 RRF 融合分数
                return c;
            })
            .toList(); // 返回融合结果列表
    }

    /**
     * 去重：按子块 ID 去重，保留首次出现的候选
     */
    private List<RecallCandidate> dedup(List<RecallCandidate> list) {
        Map<String, RecallCandidate> seen = new LinkedHashMap<>(); // 已见子块 ID 映射
        for (RecallCandidate c : list) {
            seen.putIfAbsent(c.getChildId(), c); // 保留首次出现的候选
        }
        return new ArrayList<>(seen.values()); // 返回去重后的列表
    }

    /**
     * 汇总前 5 个候选的命中信息，用于日志
     */
    private String summarizeHits(List<RecallCandidate> candidates) {
        return candidates.stream()
            .limit(5) // 取前 5 个
            .map(c -> c.getDocTitle() + "/" + c.getChildId() + "/" + c.getScore()) // 格式化
            .toList()
            .toString(); // 转为字符串
    }

    /**
     * 截断过长字符串，用于日志输出
     */
    private String abbreviate(String value) {
        if (value == null) return ""; // 空值处理
        String normalized = value.replaceAll("\\s+", " ").strip(); // 压缩空白
        int max = 1000; // 最大长度
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]"; // 超长截断
    }
}
