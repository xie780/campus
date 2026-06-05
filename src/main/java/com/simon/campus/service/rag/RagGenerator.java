package com.simon.campus.service.rag;

import com.simon.campus.common.LlmClient;
import com.simon.campus.service.admin.SystemConfigService;
import com.simon.campus.session.SessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * RAG 生成器（RAG Stage 6）：基于组装好的父块上下文，调用 LLM 流式生成基于参考资料的回答；
 * 也支持无上下文的闲聊模式
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagGenerator {

    private final LlmClient llmClient; // LLM 调用客户端
    private final SystemConfigService configService; // 系统配置服务

    /**
     * RAG 模式默认系统提示词：要求基于参考资料回答，标注来源，不编造信息
     */
    public static final String DEFAULT_RAG_SYSTEM = """
        你是SmartCampus校园智能助手，专门回答学校政策、教务规定和校园服务相关问题。
        请根据提供的参考资料回答用户问题。要求：
        1. 答案必须基于参考资料中的内容，不要凭空编造
        2. 在答案中标注来源，格式：[来源: 文档名, 第X页]
        3. 如果参考资料中没有相关信息，回复"抱歉，知识库中暂无关于此问题的相关信息，建议您联系相关部门咨询。"
        4. 语气专业但友好，回答简洁有条理
        5. 如有多条规定，使用序号或要点格式
        """; // RAG 系统提示词

    /**
     * 闲聊模式默认系统提示词：友好回应日常闲聊，引导校园相关问题
     */
    public static final String DEFAULT_CHITCHAT_SYSTEM = """
        你是SmartCampus校园智能助手，友好、专业。
        如果是日常闲聊，轻松回应；如有校园相关问题，引导用户使用知识库查询功能。
        """; // 闲聊系统提示词

    private static final String NO_CONTEXT_REPLY =
        "抱歉，知识库中暂无关于此问题的相关信息，建议您联系相关部门咨询。如需转接人工客服，请告知我。"; // 无上下文时的默认回复

    /**
     * 流式 RAG 生成：基于上下文流式生成回答，逐 token 回调
     */
    public String generateStream(String query, String context, SessionContext session,
                                  Consumer<String> onToken) throws Exception {
        if (context == null || context.isBlank()) { // 无参考上下文
            onToken.accept(NO_CONTEXT_REPLY); // 直接回调默认回复
            return NO_CONTEXT_REPLY; // 返回默认回复
        }

        String userContent = buildUserContent(query, context, session); // 构建用户消息内容
        String model = configService.get("models.rag-generator.model", "qwen-max"); // 获取模型配置
        double temperature = configService.getDouble("models.rag-generator.temperature", 0.2); // 获取温度配置
        int maxTokens = configService.getInt("models.rag-generator.max-tokens", 2048); // 获取最大 token 配置
        String systemPrompt = configService.get("prompt.rag_default", DEFAULT_RAG_SYSTEM); // 获取系统提示词
        return llmClient.chatStream(model, temperature, maxTokens, // 调用 LLM 流式生成
            LlmClient.toMaps(List.of(LlmClient.Msg.system(systemPrompt), LlmClient.Msg.user(userContent))),
            onToken); // 逐 token 回调
    }

    /**
     * 流式闲聊生成：无 RAG 上下文，直接基于对话历史生成回复
     */
    public String chitchatStream(String query, SessionContext session,
                                  Consumer<String> onToken) throws Exception {
        String history = session.buildHistoryText(5); // 获取最近 5 轮对话历史
        String userContent = history.isBlank() ? query : history + "\n用户: " + query; // 拼接历史与当前问题
        String model = configService.get("models.chitchat.model", "qwen-turbo"); // 获取闲聊模型配置
        double temperature = configService.getDouble("models.chitchat.temperature", 0.7); // 获取闲聊温度配置
        int maxTokens = configService.getInt("models.chitchat.max-tokens", 1024); // 获取最大 token 配置
        String systemPrompt = configService.get("prompt.chitchat_default", DEFAULT_CHITCHAT_SYSTEM); // 获取闲聊系统提示词
        return llmClient.chatStream(model, temperature, maxTokens, // 调用 LLM 流式生成
            LlmClient.toMaps(List.of(LlmClient.Msg.system(systemPrompt), LlmClient.Msg.user(userContent))),
            onToken); // 逐 token 回调
    }

    /**
     * 构建 RAG 用户消息：拼接对话历史、参考资料和用户问题
     */
    private String buildUserContent(String query, String context, SessionContext session) {
        String history = session.buildHistoryText(3); // 获取最近 3 轮对话历史
        StringBuilder sb = new StringBuilder(); // 消息构建器
        if (!history.isBlank()) { // 有对话历史
            sb.append("对话历史（供参考）：\n").append(history).append("\n\n"); // 追加历史
        }
        sb.append("参考资料：\n").append(context).append("\n\n"); // 追加参考资料
        sb.append("用户问题：").append(query); // 追加用户问题
        return sb.toString(); // 返回完整用户消息
    }
}
