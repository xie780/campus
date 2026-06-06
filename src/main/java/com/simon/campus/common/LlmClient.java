package com.simon.campus.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * LLM 客户端：基于 Spring AI ChatClient 封装，支持阻塞式和流式聊天补全。
 * 自动适配 DashScope OpenAI 兼容端点，保持原有 API 接口不变。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LlmClient {

    private final ChatClient chatClient;
    private final ChatModel chatModel;

    // ── Blocking chat completion ──────────────────────────────────────────────

    /**
     * 阻塞式聊天补全：发送请求并等待完整响应
     */
    public String chat(String model, double temperature, int maxTokens,
                       List<Map<String, String>> messages) throws Exception {
        long start = System.currentTimeMillis();
        log.info("[MODEL_CALL] type=chat model={} temperature={} maxTokens={} messages={}",
            model, temperature, maxTokens, summarize(messages));

        String content = chatClient.prompt()
            .options(OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build())
            .advisors(new SimpleLoggerAdvisor())
            .messages(convertMessages(messages))
            .call()
            .content();

        log.info("[MODEL_RETURN] type=chat model={} costMs={} response={}",
            model, System.currentTimeMillis() - start, abbreviate(content));
        return content != null ? content : "";
    }

    // ── Streaming chat completion ─────────────────────────────────────────────

    /**
     * 流式聊天补全：通过 SSE 逐 token 回调，流结束后返回完整内容
     */
    public String chatStream(String model, double temperature, int maxTokens,
                              List<Map<String, String>> messages,
                              Consumer<String> onToken) throws Exception {
        long start = System.currentTimeMillis();
        log.info("[MODEL_CALL] type=stream model={} temperature={} maxTokens={} messages={}",
            model, temperature, maxTokens, summarize(messages));

        StringBuilder fullContent = new StringBuilder();

        Flux<String> flux = chatClient.prompt()
            .options(OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build())
            .advisors(new SimpleLoggerAdvisor())
            .messages(convertMessages(messages))
            .stream()
            .content();

        // 同步收集流式响应（阻塞直到流结束）
        flux.doOnNext(token -> {
            if (token != null) {
                fullContent.append(token);
                onToken.accept(token);
            }
        }).doOnError(e -> log.error("Stream error: {}", e.getMessage()))
          .blockLast();

        log.info("[MODEL_RETURN] type=stream model={} costMs={} response={}",
            model, System.currentTimeMillis() - start, abbreviate(fullContent.toString()));
        return fullContent.toString();
    }

    // ── Message helpers ───────────────────────────────────────────────────────

    /**
     * 将 Map 消息列表转换为 Spring AI Message 列表
     */
    private List<org.springframework.ai.chat.messages.Message> convertMessages(List<Map<String, String>> source) {
        List<org.springframework.ai.chat.messages.Message> target = new ArrayList<>();
        for (Map<String, String> msg : source) {
            String role = msg.getOrDefault("role", "user");
            String content = msg.getOrDefault("content", "");
            org.springframework.ai.chat.messages.Message aiMsg;
            switch (role) {
                case "system":
                    aiMsg = new org.springframework.ai.chat.messages.SystemMessage(content);
                    break;
                case "assistant":
                    aiMsg = new org.springframework.ai.chat.messages.AssistantMessage(content);
                    break;
                case "function":
                case "tool":
                    aiMsg = new org.springframework.ai.chat.messages.ToolResponseMessage(new ArrayList<>());
                    break;
                default:
                    aiMsg = new org.springframework.ai.chat.messages.UserMessage(content);
                    break;
            }
            target.add(aiMsg);
        }
        return target;
    }

    /**
     * 将 Msg 列表转换为 Map 格式（保持兼容）
     */
    public static List<Map<String, String>> toMaps(List<Msg> messages) {
        List<Map<String, String>> raw = new ArrayList<>();
        for (Msg m : messages) raw.add(Map.of("role", m.role(), "content", m.content()));
        return raw;
    }

    // ── Message builder helpers ───────────────────────────────────────────────

    public record Msg(String role, String content) {
        public static Msg system(String content) { return new Msg("system", content); }
        public static Msg user(String content)   { return new Msg("user", content); }
        public static Msg assistant(String c)    { return new Msg("assistant", c); }
    }

    public static Map<String, String> systemMsg(String content) {
        return Map.of("role", "system", "content", content);
    }

    public static Map<String, String> userMsg(String content) {
        return Map.of("role", "user", "content", content);
    }

    public static Map<String, String> assistantMsg(String content) {
        return Map.of("role", "assistant", "content", content);
    }

    // ── Logging helpers ───────────────────────────────────────────────────────

    private String summarize(Object value) {
        return abbreviate(String.valueOf(value));
    }

    private String abbreviate(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").strip();
        int max = 4000;
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]";
    }
}