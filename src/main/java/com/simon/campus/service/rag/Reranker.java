package com.simon.campus.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.campus.model.dto.RecallCandidate;
import com.simon.campus.service.admin.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * 重排序器（RAG Stage 4）：调用 DashScope gte-rerank 模型对召回候选进行语义精排，
 * 过滤低分候选（score < 0.3），保留 top 8–12 高质量结果
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class Reranker {

    private static final String RERANK_URL =
        "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"; // DashScope 重排序 API 地址
    private final SystemConfigService configService; // 系统配置服务

    @Value("${dashscope.api-key}")
    private String apiKey; // DashScope API 密钥

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(); // HTTP 客户端（10 秒连接超时）
    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 序列化工具

    /**
     * 对召回候选进行重排序：调用 gte-rerank API，按语义相关性重新评分并过滤低分结果
     */
    public List<RecallCandidate> rerank(String query, List<RecallCandidate> candidates) {
        if (candidates.isEmpty()) return candidates; // 无候选则直接返回
        try {
            long start = System.currentTimeMillis(); // 记录开始时间
            List<String> docs = candidates.stream().map(RecallCandidate::getContent).toList(); // 提取候选文本列表
            int topN = configService.getInt("rag.rerank.top_n", 12); // 获取 topN 配置
            double minScore = configService.getDouble("rag.rerank.score_thresh", 0.3); // 获取最低分数阈值
            log.info("[MODEL_CALL] type=rerank model=gte-rerank query={} docs={}",
                abbreviate(query), abbreviate(String.valueOf(docs))); // 记录模型调用日志

            Map<String, Object> body = Map.of( // 构建请求体
                "model", "gte-rerank", // 重排序模型名称
                "input", Map.of("query", query, "documents", docs), // 查询与候选文档
                "parameters", Map.of("top_n", Math.min(topN, docs.size()), "return_documents", false) // 返回参数
            );

            HttpRequest request = HttpRequest.newBuilder() // 构建 HTTP 请求
                .uri(URI.create(RERANK_URL)) // API 地址
                .header("Authorization", "Bearer " + apiKey) // 认证头
                .header("Content-Type", "application/json") // 内容类型
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))) // 请求体
                .timeout(Duration.ofSeconds(30)) // 30 秒超时
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求
            if (response.statusCode() != 200) { // 非 200 响应
                log.warn("Rerank API returned {}: {}", response.statusCode(), response.body()); // 记录警告
                return candidates; // 回退到原始排序
            }

            JsonNode root = objectMapper.readTree(response.body()); // 解析响应 JSON
            log.info("[MODEL_RETURN] type=rerank model=gte-rerank costMs={} response={}",
                System.currentTimeMillis() - start, abbreviate(response.body())); // 记录模型返回日志
            JsonNode results = root.path("output").path("results"); // 提取重排序结果节点

            List<RecallCandidate> reranked = new ArrayList<>(); // 重排序后的候选列表
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(); // 原始候选索引
                double score = r.path("relevance_score").asDouble(); // 相关性分数
                if (score >= minScore && idx < candidates.size()) { // 分数达标且索引有效
                    RecallCandidate c = candidates.get(idx); // 获取原始候选
                    c.setScore(score); // 更新为重排序分数
                    reranked.add(c); // 加入重排序结果
                }
            }
            log.debug("Reranker: {} in → {} out (threshold={})", candidates.size(), reranked.size(), minScore); // 记录过滤统计
            return reranked.isEmpty() ? candidates.subList(0, Math.min(5, candidates.size())) : reranked; // 若全部被过滤则取前 5 个原始候选
        } catch (Exception e) {
            log.warn("Reranker failed, using original order: {}", e.getMessage()); // 重排序失败时记录警告
            return fallbackCandidates(candidates); // 回退到降级策略
        }
    }

    /**
     * 降级策略：直接取原始候选的前 topN 个
     */
    List<RecallCandidate> fallbackCandidates(List<RecallCandidate> candidates) {
        int topN = configService.getInt("rag.rerank.top_n", 12); // 获取 topN 配置
        return candidates.subList(0, Math.min(topN, candidates.size())); // 取前 topN 个
    }

    /**
     * 截断过长字符串，用于日志输出
     */
    private String abbreviate(String value) {
        if (value == null) return ""; // 空值处理
        String normalized = value.replaceAll("\\s+", " ").strip(); // 压缩空白
        int max = 3000; // 最大长度
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]"; // 超长截断
    }
}
