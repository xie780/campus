package com.simon.campus.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.campus.service.admin.SystemConfigService;
import com.simon.campus.session.SessionContext;
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
import java.util.function.Consumer;

/**
 * 工具调用编排器：基于 DashScope OpenAI 兼容的 function calling 机制，
 * 流程为 LLM 决策 → 执行工具 → LLM 流式生成最终回答
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ToolCaller {

    private final AcademicCalendarTool calendarTool; // 校历查询工具
    private final CourseSelectionTool courseSelectionTool; // 选课查询工具
    private final DepartmentContactTool departmentContactTool; // 部门联系方式查询工具
    private final HumanTicketTool humanTicketTool; // 人工客服工单工具
    private final SystemConfigService configService; // 系统配置服务
    private final ObjectMapper objectMapper; // JSON 序列化工具

    @Value("${dashscope.api-key}")
    private String apiKey; // DashScope API 密钥

    @Value("${dashscope.base-url}")
    private String baseUrl; // DashScope API 基础地址

    @Value("${models.tool-caller.model}")
    private String model; // 工具调用使用的 LLM 模型

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(); // HTTP 客户端（10 秒连接超时）

    // 工具定义列表，用于 DashScope function calling
    private static final List<Map<String, Object>> TOOL_DEFINITIONS = buildToolDefinitions(); // 工具函数定义

    /**
     * 教务工具默认系统提示词
     */
    public static final String DEFAULT_ACADEMIC_SYSTEM_PROMPT =
        "你是SmartCampus校园智能助手，擅长查询教务信息。" +
        "请根据用户需求选择合适的工具获取准确信息，并以友好清晰的方式回答。" +
        "当前时间：2026年春季学期（2025-2026-2）。"; // 教务工具系统提示词

    /**
     * 工具调用结果：最终回答 + 工具执行结果
     */
    public record ToolCallResult(
        String answer,
        ToolResult toolResult
    ) {}

    /**
     * 执行工具调用流程：LLM 决策 → 工具执行 → 流式生成最终回答
     */
    public ToolCallResult call(String query, SessionContext session, Consumer<String> onToken) {
        try {
            // 检查是否有工具启用
            if (!configService.getBool("tool.query_academic_calendar.enabled", true) &&
                !configService.getBool("tool.query_course_selection.enabled", true) &&
                !configService.getBool("tool.query_department_contact.enabled", true)) { // 所有工具均未启用
                return fallbackToText(query, onToken); // 降级为纯文本回复
            }

            List<Map<String, Object>> messages = buildMessages(query, session); // 构建消息列表
            String modelToUse = configService.get("models.tool-caller.model", model); // 获取模型配置
            log.info("[MODEL_CALL] type=tool_decision model={} messages={}",
                modelToUse, abbreviate(String.valueOf(messages))); // 记录模型调用日志

            // Step 1: 请求 LLM 决策调用哪个工具
            long decisionStart = System.currentTimeMillis(); // 记录决策开始时间
            Map<String, Object> body = buildBody(modelToUse, messages, false); // 构建非流式请求体
            HttpRequest request = buildRequest(body); // 构建 HTTP 请求
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求

            if (response.statusCode() != 200) { // 非 200 响应
                log.warn("ToolCaller step1 failed [{}]: {}", response.statusCode(), response.body()); // 记录警告
                return fallbackToText(query, onToken); // 降级回复
            }

            JsonNode root = objectMapper.readTree(response.body()); // 解析响应 JSON
            log.info("[MODEL_RETURN] type=tool_decision model={} costMs={} response={}",
                modelToUse, System.currentTimeMillis() - decisionStart, abbreviate(response.body())); // 记录模型返回日志
            JsonNode choice = root.path("choices").get(0); // 获取第一个选择
            JsonNode message = choice.path("message"); // 获取消息节点
            JsonNode toolCalls = message.path("tool_calls"); // 获取工具调用节点

            // 如果 LLM 未调用工具，直接返回文本回答
            if (toolCalls.isMissingNode() || toolCalls.isEmpty()) { // 无工具调用
                String directAnswer = message.path("content").asText(""); // 获取直接回答
                onToken.accept(directAnswer); // 回调输出
                return new ToolCallResult(directAnswer, null); // 返回直接回答
            }

            // Step 2: 执行工具
            JsonNode firstCall = toolCalls.get(0); // 获取第一个工具调用
            String callId = firstCall.path("id").asText(); // 工具调用 ID
            String funcName = firstCall.path("function").path("name").asText(); // 函数名称
            String argsJson  = firstCall.path("function").path("arguments").asText("{}"); // 函数参数 JSON

            log.info("Tool call: {} args={}", funcName, argsJson); // 记录工具调用
            ToolResult toolResult = executeTool(funcName, argsJson, session); // 执行工具
            log.info("[TOOL_FLOW] tool={} args={} result={}",
                funcName, abbreviate(argsJson), abbreviate(objectMapper.writeValueAsString(toolResult))); // 记录工具流程

            // Step 3: 将工具结果加入消息列表，流式生成最终回答
            messages = new ArrayList<>(messages); // 复制消息列表
            Map<String, Object> assistantMsg = new LinkedHashMap<>(); // 构建助手消息
            assistantMsg.put("role", "assistant"); // 角色
            assistantMsg.put("content", ""); // 内容为空
            assistantMsg.put("tool_calls", List.of(Map.of( // 工具调用信息
                "id", callId,
                "type", "function",
                "function", Map.of("name", funcName, "arguments", argsJson)
            )));
            messages.add(assistantMsg); // 添加助手消息

            Map<String, Object> toolMsg = new LinkedHashMap<>(); // 构建工具结果消息
            toolMsg.put("role", "tool"); // 角色为 tool
            toolMsg.put("tool_call_id", callId); // 关联的工具调用 ID
            toolMsg.put("content", objectMapper.writeValueAsString(toolResult.getData())); // 工具返回数据
            messages.add(toolMsg); // 添加工具结果消息

            // Step 4: 流式生成最终回答
            String finalAnswer = streamFinalAnswer(modelToUse, messages, onToken); // 流式生成
            return new ToolCallResult(finalAnswer, toolResult); // 返回最终结果

        } catch (Exception e) {
            log.error("ToolCaller failed: {}", e.getMessage(), e); // 记录错误
            return fallbackToText(query, onToken); // 降级回复
        }
    }

    /**
     * 根据工具名称执行对应的工具逻辑
     */
    private ToolResult executeTool(String name, String argsJson, SessionContext session) throws Exception {
        JsonNode args = objectMapper.readTree(argsJson); // 解析参数 JSON
        return switch (name) {
            case "query_academic_calendar" -> { // 校历查询
                String term = args.path("term").asText(null); // 获取学期参数
                yield calendarTool.query(term); // 执行校历查询
            }
            case "query_course_selection" -> { // 选课查询
                String term = args.path("term").asText(null); // 获取学期参数
                yield courseSelectionTool.query(term); // 执行选课查询
            }
            case "query_department_contact" -> { // 部门联系方式查询
                String dept = args.path("department").asText(null); // 获取部门参数
                yield departmentContactTool.query(dept); // 执行部门联系方式查询
            }
            case "create_human_ticket" -> { // 创建人工客服工单
                String summary  = args.path("summary").asText(null); // 获取问题摘要
                String urgency  = args.path("urgency").asText("MEDIUM"); // 获取紧急程度
                String sessId   = session != null ? session.getSessionId() : null; // 获取会话 ID
                Long userId     = session != null ? session.getUserId() : null; // 获取用户 ID
                yield humanTicketTool.create(sessId, userId, summary, urgency); // 创建工单
            }
            default -> ToolResult.builder().success(false).toolName(name)
                .error("Unknown tool: " + name).build(); // 未知工具
        };
    }

    /**
     * 流式生成最终回答：将工具结果发送给 LLM，逐 token 回调输出
     */
    private String streamFinalAnswer(String modelName, List<Map<String, Object>> messages,
                                      Consumer<String> onToken) throws Exception {
        long start = System.currentTimeMillis(); // 记录开始时间
        log.info("[MODEL_CALL] type=tool_final_stream model={} messages={}",
            modelName, abbreviate(String.valueOf(messages))); // 记录模型调用日志
        Map<String, Object> body = buildBody(modelName, messages, true); // 构建流式请求体
        HttpRequest request = buildRequest(body); // 构建 HTTP 请求
        HttpResponse<java.io.InputStream> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream()); // 发送请求（输入流响应）

        if (response.statusCode() != 200) { // 非 200 响应
            String err = new String(response.body().readAllBytes()); // 读取错误信息
            throw new RuntimeException("Stream failed [" + response.statusCode() + "]: " + err); // 抛出异常
        }

        StringBuilder full = new StringBuilder(); // 完整回答构建器
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body()))) { // 读取流
            String line;
            while ((line = reader.readLine()) != null) { // 逐行读取
                if (!line.startsWith("data: ")) continue; // 跳过非数据行
                String data = line.substring(6).trim(); // 提取数据部分
                if ("[DONE]".equals(data)) break; // 流结束标记
                try {
                    JsonNode r = objectMapper.readTree(data); // 解析 SSE 数据
                    String token = r.path("choices").get(0).path("delta").path("content").asText(""); // 提取 token
                    if (!token.isEmpty()) { full.append(token); onToken.accept(token); } // 回调输出
                } catch (Exception ignored) {} // 忽略解析错误
            }
        }
        log.info("[MODEL_RETURN] type=tool_final_stream model={} costMs={} response={}",
            modelName, System.currentTimeMillis() - start, abbreviate(full.toString())); // 记录模型返回日志
        return full.toString(); // 返回完整回答
    }

    /**
     * 降级为纯文本回复（工具不可用时）
     */
    private ToolCallResult fallbackToText(String query, Consumer<String> onToken) {
        String msg = "抱歉，工具服务暂时不可用，请稍后重试或直接联系相关部门。"; // 降级回复文本
        onToken.accept(msg); // 回调输出
        return new ToolCallResult(msg, null); // 返回降级结果
    }

    /**
     * 构建消息列表：系统提示 + 对话历史 + 用户问题
     */
    private List<Map<String, Object>> buildMessages(String query, SessionContext session) {
        List<Map<String, Object>> msgs = new ArrayList<>(); // 消息列表
        msgs.add(Map.of("role", "system", "content", // 系统提示消息
            configService.get("prompt.academic_default", DEFAULT_ACADEMIC_SYSTEM_PROMPT))); // 获取系统提示词
        String history = session != null ? session.buildHistoryText(3) : ""; // 获取对话历史
        String content = history.isBlank() ? query : history + "\n用户: " + query; // 拼接历史与当前问题
        msgs.add(Map.of("role", "user", "content", content)); // 用户消息
        return msgs; // 返回消息列表
    }

    /**
     * 构建 API 请求体
     */
    private Map<String, Object> buildBody(String modelName, List<Map<String, Object>> messages,
                                           boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>(); // 请求体
        body.put("model", modelName); // 模型名称
        body.put("messages", messages); // 消息列表
        body.put("tools", getEnabledTools()); // 工具定义
        body.put("tool_choice", "auto"); // 工具选择策略：自动
        body.put("temperature", configService.getDouble("models.tool-caller.temperature", 0.1)); // 温度
        body.put("max_tokens", configService.getInt("models.tool-caller.max-tokens", 1024)); // 最大 token
        if (stream) body.put("stream", true); // 流式模式
        return body; // 返回请求体
    }

    /**
     * 获取已启用的工具定义列表
     */
    private List<Map<String, Object>> getEnabledTools() {
        List<Map<String, Object>> enabled = new ArrayList<>(); // 已启用工具列表
        for (Map<String, Object> tool : TOOL_DEFINITIONS) { // 遍历所有工具定义
            String name = (String) ((Map<?,?>) tool.get("function")).get("name"); // 获取工具名称
            String key = "tool." + name + ".enabled"; // 构建配置键
            if (configService.getBool(key, true)) enabled.add(tool); // 已启用则加入列表
        }
        return enabled.isEmpty() ? TOOL_DEFINITIONS : enabled; // 若全部禁用则返回全部定义
    }

    /**
     * 构建 HTTP 请求
     */
    private HttpRequest buildRequest(Map<String, Object> body) throws Exception {
        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/chat/completions")) // API 端点
            .header("Authorization", "Bearer " + apiKey) // 认证头
            .header("Content-Type", "application/json") // 内容类型
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body))) // 请求体
            .timeout(Duration.ofSeconds(90)) // 90 秒超时
            .build(); // 构建请求
    }

    /**
     * 构建工具函数定义列表，用于 DashScope function calling
     */
    private static List<Map<String, Object>> buildToolDefinitions() {
        return List.of(
            tool("query_academic_calendar", "查询校历安排，包括开学放假考试等事件", // 校历查询工具
                Map.of("term", param("string", "学期，格式如 2025-2026-2，不填则查当前学期")),
                List.of()),
            tool("query_course_selection", "查询选课安排，包括各轮选课和退课时间", // 选课查询工具
                Map.of("term", param("string", "学期，格式如 2025-2026-2，不填则查当前学期")),
                List.of()),
            tool("query_department_contact", "查询院系或行政部门联系方式", // 部门联系方式查询工具
                Map.of("department", param("string", "部门名称，如教务处、计算机学院，不填则返回所有")),
                List.of()),
            tool("create_human_ticket", "创建人工客服工单，转接给老师处理", // 人工客服工单工具
                Map.of(
                    "summary",  param("string", "问题摘要，描述用户的问题或诉求"),
                    "urgency",  param("string", "紧急程度：HIGH/MEDIUM/LOW")
                ),
                List.of("summary")) // summary 为必填参数
        );
    }

    /**
     * 构建单个工具定义
     */
    private static Map<String, Object> tool(String name, String description,
                                             Map<String, Map<String, Object>> properties,
                                             List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>(); // 参数定义
        params.put("type", "object"); // 参数类型
        params.put("properties", properties); // 参数属性
        if (!required.isEmpty()) params.put("required", required); // 必填参数
        return Map.of(
            "type", "function", // 类型为 function
            "function", Map.of("name", name, "description", description, "parameters", params) // 函数定义
        );
    }

    /**
     * 构建单个参数定义
     */
    private static Map<String, Object> param(String type, String description) {
        return Map.of("type", type, "description", description); // 参数类型和描述
    }

    /**
     * 截断过长字符串，用于日志输出
     */
    private String abbreviate(String value) {
        if (value == null) return ""; // 空值处理
        String normalized = value.replaceAll("\\s+", " ").strip(); // 压缩空白
        int max = 4000; // 最大长度
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]"; // 超长截断
    }
}
