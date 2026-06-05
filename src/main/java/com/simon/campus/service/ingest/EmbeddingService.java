package com.simon.campus.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 向量嵌入服务：调用 DashScope text-embedding-v3 模型将文本批量转换为向量
 */
@Service
@Slf4j
public class EmbeddingService {

    private static final String EMBED_URL = // DashScope 嵌入 API 地址
        "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    static final int BATCH_SIZE = 10; // 每批最大文本数
    private static final int DIMENSION  = 1024; // 向量维度

    @Value("${dashscope.api-key}")
    private String apiKey; // DashScope API Key

    private final HttpClient httpClient = HttpClient.newBuilder() // HTTP 客户端
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 序列化器

    /**
     * 批量向量化：按 BATCH_SIZE 分批调用，返回与输入顺序一致的向量列表
     */
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        List<float[]> result = new ArrayList<>(); // 结果列表
        for (int i = 0; i < texts.size(); i += BATCH_SIZE) { // 分批处理
            List<String> batch = texts.subList(i, Math.min(i + BATCH_SIZE, texts.size())); // 当前批次
            result.addAll(embedSingleBatch(batch)); // 调用单批嵌入
        }
        return result; // 返回全部向量
    }

    /**
     * 单条文本向量化
     */
    public float[] embedOne(String text) throws Exception {
        List<float[]> res = embedSingleBatch(List.of(text)); // 单条作为一批
        return res.get(0); // 返回第一个向量
    }

    /**
     * 调用 DashScope 嵌入 API 处理单批文本
     */
    private List<float[]> embedSingleBatch(List<String> texts) throws Exception {
        long start = System.currentTimeMillis(); // 记录开始时间
        log.info("[MODEL_CALL] type=embedding model=text-embedding-v3 count={} texts={}",
            texts.size(), abbreviate(String.valueOf(texts))); // 记录调用日志
        Map<String, Object> body = Map.of( // 构建请求体
            "model", "text-embedding-v3",
            "input", Map.of("texts", texts),
            "parameters", Map.of("dimension", DIMENSION)
        );

        HttpRequest request = HttpRequest.newBuilder() // 构建 HTTP 请求
            .uri(URI.create(EMBED_URL))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(60))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求
        if (response.statusCode() != 200) { // 请求失败
            throw new RuntimeException("DashScope Embedding 调用失败 [" + response.statusCode() + "]: " + response.body());
        }

        List<float[]> embeddings = parseEmbeddingResponse(objectMapper, response.body(), texts.size(), DIMENSION); // 解析响应
        log.info("[MODEL_RETURN] type=embedding model=text-embedding-v3 costMs={} count={} dimension={} response={}",
            System.currentTimeMillis() - start, embeddings.size(), DIMENSION, abbreviate(response.body())); // 记录返回日志
        return embeddings; // 返回向量列表
    }

    /**
     * 解析 DashScope 嵌入 API 响应，按 text_index 排序返回向量列表
     */
    static List<float[]> parseEmbeddingResponse(ObjectMapper objectMapper, String responseBody,
                                                int expectedCount, int dimension) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody); // 解析 JSON
        JsonNode embeddings = root.path("output").path("embeddings"); // 获取嵌入数组
        if (!embeddings.isArray()) { // 缺少嵌入数组
            throw new RuntimeException("DashScope Embedding 响应缺少 output.embeddings");
        }

        List<float[]> sorted = new ArrayList<>(Collections.nCopies(expectedCount, null)); // 预分配排序数组
        for (JsonNode node : embeddings) { // 遍历每个嵌入
            int idx = node.has("text_index") ? node.path("text_index").asInt() : node.path("index").asInt(); // 获取索引
            if (idx < 0 || idx >= expectedCount) { // 索引越界
                throw new RuntimeException("DashScope Embedding 响应 text_index 越界: " + idx);
            }
            JsonNode arr = node.path("embedding"); // 获取向量数组
            if (!arr.isArray() || arr.size() != dimension) { // 维度异常
                throw new RuntimeException("DashScope Embedding 维度异常: expected=" + dimension + ", actual=" + arr.size());
            }
            float[] vec = new float[dimension]; // 构建向量
            for (int i = 0; i < dimension; i++) {
                vec[i] = (float) arr.get(i).asDouble(); // 逐个转换
            }
            sorted.set(idx, vec); // 按索引放入排序数组
        }

        for (int i = 0; i < sorted.size(); i++) { // 检查缺失索引
            if (sorted.get(i) == null) {
                throw new RuntimeException("DashScope Embedding 响应缺少 text_index=" + i + " 的向量");
            }
        }
        return sorted; // 返回排序后的向量列表
    }

    /**
     * 截断过长的日志字符串
     */
    private String abbreviate(String value) {
        if (value == null) return ""; // 空值返回空串
        String normalized = value.replaceAll("\\s+", " ").strip(); // 压缩空白
        int max = 3000; // 最大长度
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]"; // 超长截断
    }
}
