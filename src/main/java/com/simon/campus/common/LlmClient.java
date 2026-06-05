package com.simon.campus.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * DashScope OpenAI 兼容端点客户端：支持阻塞式和流式（SSE）聊天补全，
 * 封装 HTTP 请求构建、响应解析和消息格式转换
 */
@Component
@Slf4j
public class LlmClient {

    @Value("${dashscope.api-key}")
    private String apiKey; // DashScope API 密钥

    @Value("${dashscope.base-url}")
    private String baseUrl; // DashScope 基础 URL

    private final HttpClient httpClient = HttpClient.newBuilder() // HTTP 客户端
        .connectTimeout(Duration.ofSeconds(10)) // 连接超时 10 秒
        .build(); // 构建客户端
    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 序列化器

    // ── Blocking chat completion ──────────────────────────────────────────────

    /**
     * 阻塞式聊天补全：发送请求并等待完整响应
     */
    public String chat(String model, double temperature, int maxTokens,
                       List<Map<String, String>> messages) throws Exception {
        long start = System.currentTimeMillis(); // 记录开始时间
        log.info("[MODEL_CALL] type=chat model={} temperature={} maxTokens={} messages={}",
            model, temperature, maxTokens, summarize(messages)); // 记录调用日志
        Map<String, Object> body = buildBody(model, temperature, maxTokens, messages, false); // 构建非流式请求体
        HttpRequest request = buildRequest(body); // 构建 HTTP 请求
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求

        if (response.statusCode() != 200) { // 响应非 200
            throw new RuntimeException("LLM call failed [" + response.statusCode() + "]: " + response.body()); // 抛出异常
        }
        JsonNode root = objectMapper.readTree(response.body()); // 解析响应 JSON
        String content = root.path("choices").get(0).path("message").path("content").asText(); // 提取回复内容
        log.info("[MODEL_RETURN] type=chat model={} costMs={} response={}",
            model, System.currentTimeMillis() - start, abbreviate(content)); // 记录返回日志
        return content; // 返回回复内容
    }

    // ── Streaming chat completion ─────────────────────────────────────────────

    /**
     * 流式聊天补全：通过 SSE 逐 token 回调，流结束后返回完整内容
     */
    public String chatStream(String model, double temperature, int maxTokens,
                              List<Map<String, String>> messages,
                              Consumer<String> onToken) throws Exception {
        long start = System.currentTimeMillis(); // 记录开始时间
        log.info("[MODEL_CALL] type=stream model={} temperature={} maxTokens={} messages={}",
            model, temperature, maxTokens, summarize(messages)); // 记录调用日志
        Map<String, Object> body = buildBody(model, temperature, maxTokens, messages, true); // 构建流式请求体
        HttpRequest request = buildRequest(body); // 构建 HTTP 请求

        HttpResponse<java.io.InputStream> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()); // 发送请求获取输入流

        if (response.statusCode() != 200) { // 响应非 200
            String err = new String(response.body().readAllBytes()); // 读取错误信息
            throw new RuntimeException("LLM stream failed [" + response.statusCode() + "]: " + err); // 抛出异常
        }

        StringBuilder fullContent = new StringBuilder(); // 累积完整内容
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) { // 读取流
            String line;
            while ((line = reader.readLine()) != null) { // 逐行读取
                if (!line.startsWith("data: ")) continue; // 跳过非数据行
                String data = line.substring(6).trim(); // 提取数据部分
                if ("[DONE]".equals(data)) break; // 流结束标记
                try {
                    JsonNode root = objectMapper.readTree(data); // 解析 SSE 数据
                    JsonNode delta = root.path("choices").get(0).path("delta"); // 获取增量内容
                    String token = delta.path("content").asText(""); // 提取 token
                    if (!token.isEmpty()) { // token 非空
                        fullContent.append(token); // 累积内容
                        onToken.accept(token); // 回调 token
                    }
                } catch (Exception e) {
                    log.debug("Skip unparseable SSE line: {}", data); // 跳过不可解析行
                }
            }
        }
        log.info("[MODEL_RETURN] type=stream model={} costMs={} response={}",
            model, System.currentTimeMillis() - start, abbreviate(fullContent.toString())); // 记录返回日志
        return fullContent.toString(); // 返回完整内容
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * 构建请求体：包含模型、消息、温度、最大 token 和流式标志
     */
    private Map<String, Object> buildBody(String model, double temperature, int maxTokens,
                                           List<Map<String, String>> messages, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>(); // 创建请求体
        body.put("model", model); // 设置模型
        body.put("messages", messages); // 设置消息列表
        body.put("temperature", temperature); // 设置温度
        body.put("max_tokens", maxTokens); // 设置最大 token
        if (stream) body.put("stream", true); // 流式模式标志
        return body; // 返回请求体
    }

    /**
     * 构建 HTTP 请求：设置 URL、认证头、内容类型和超时
     */
    private HttpRequest buildRequest(Map<String, Object> body) throws Exception {
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions")) // 请求 URL
            .header("Authorization", "Bearer " + apiKey) // 认证头
            .header("Content-Type", "application/json") // 内容类型
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))) // 请求体
            .timeout(Duration.ofSeconds(60)) // 超时 60 秒
            .build(); // 构建请求
    }

    /**
     * 将 Msg 列表转换为 Map 格式
     */
    public static List<Map<String, String>> toMaps(List<Msg> messages) {
        List<Map<String, String>> raw = new ArrayList<>(); // 创建结果列表
        for (Msg m : messages) raw.add(Map.of("role", m.role(), "content", m.content())); // 逐条转换
        return raw; // 返回 Map 列表
    }

    // ── Message builder helpers ───────────────────────────────────────────────

    /**
     * 消息记录：包含角色和内容
     */
    public record Msg(String role, String content) {
        public static Msg system(String content) { return new Msg("system", content); } // 系统消息
        public static Msg user(String content)   { return new Msg("user", content); } // 用户消息
        public static Msg assistant(String c)    { return new Msg("assistant", c); } // 助手消息
    }

    /**
     * 创建系统消息 Map
     */
    public static Map<String, String> systemMsg(String content) {
        return Map.of("role", "system", "content", content); // 系统消息
    }

    /**
     * 创建用户消息 Map
     */
    public static Map<String, String> userMsg(String content) {
        return Map.of("role", "user", "content", content); // 用户消息
    }

    /**
     * 创建助手消息 Map
     */
    public static Map<String, String> assistantMsg(String content) {
        return Map.of("role", "assistant", "content", content); // 助手消息
    }

    /**
     * 摘要化对象
     */
    private String summarize(Object value) {
        return abbreviate(String.valueOf(value)); // 缩写字符串
    }

    /**
     * 缩写长字符串，超过 4000 字符时截断
     */
    private String abbreviate(String value) {
        if (value == null) return ""; // 空值返回空串
        String normalized = value.replaceAll("\\s+", " ").strip(); // 规范化空白
        int max = 4000; // 最大长度
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]"; // 截断
    }
}
