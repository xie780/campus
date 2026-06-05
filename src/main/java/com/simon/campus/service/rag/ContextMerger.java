package com.simon.campus.service.rag;

import com.simon.campus.common.LlmClient;
import com.simon.campus.session.SessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 上下文融合器（RAG Stage 1）：利用对话历史消除指代和省略，将用户最新问题改写为完整独立问题
 * 例如将"那绩点要求是多少"改写为"计算机学院转专业的绩点要求是多少"
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContextMerger {

    private final LlmClient llmClient; // LLM 调用客户端

    @Value("${models.query-rewriter.model}")
    private String model; // 使用的 LLM 模型名称

    private static final String SYSTEM_PROMPT = """
        你是一个上下文融合专家。给定对话历史和用户的最新问题，输出一个完整独立的问题。
        要求：
        1. 消除指代词（如"那个"、"它"、"他们"），替换为具体对象
        2. 补全省略的主语/宾语
        3. 如果问题已经完整独立，直接返回原问题
        4. 只输出问题本身，不要有任何解释
        """; // 上下文融合系统提示词

    /**
     * 融合对话历史与最新问题，输出完整独立的问题
     */
    public String merge(String latestQuestion, SessionContext session) {
        String history = session.buildHistoryText(5); // 获取最近 5 轮对话历史文本
        if (history.isBlank()) { // 无历史对话则无需融合
            return latestQuestion; // 直接返回原问题
        }
        try {
            String userContent = "对话历史：\n" + history + "\n\n用户最新问题：" + latestQuestion; // 拼接历史与当前问题
            String result = llmClient.chat(model, 0.1, 256, // 低温度保证稳定输出
                List.of(LlmClient.systemMsg(SYSTEM_PROMPT), LlmClient.userMsg(userContent))); // 调用 LLM
            String merged = result.strip(); // 去除首尾空白
            log.debug("ContextMerger: '{}' → '{}'", latestQuestion, merged); // 记录融合前后对比
            return merged.isEmpty() ? latestQuestion : merged; // 若结果为空则回退到原问题
        } catch (Exception e) {
            log.warn("ContextMerger failed, using original: {}", e.getMessage()); // 融合失败时记录警告
            return latestQuestion; // 回退到原问题
        }
    }
}
