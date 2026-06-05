package com.simon.campus.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.campus.common.LlmClient;
import com.simon.campus.model.dto.QueryExpansion;
import com.simon.campus.service.admin.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询改写器（RAG Stage 2）：将用户原始查询扩展为主查询、子查询和关键词，提升多路召回的覆盖率
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QueryRewriter {

    private final LlmClient llmClient; // LLM 调用客户端
    private final ObjectMapper objectMapper; // JSON 序列化/反序列化工具
    private final SystemConfigService configService; // 系统配置服务

    private static final String SYSTEM_PROMPT = """
        你是一个查询改写专家，帮助改善知识库检索效果。
        给定用户问题，输出严格的JSON格式（不要有markdown代码块）：
        {
          "mainQuery": "改写后的完整核心问题",
          "subQueries": ["从不同角度的子问题1", "子问题2"],
          "keywords": ["关键词1", "关键词2", "关键词3"]
        }
        要求：
        - mainQuery 是最重要的独立完整问题
        - subQueries 2-3个，覆盖不同检索角度
        - keywords 3-6个核心词（名词为主）
        只输出JSON，不要有任何其他文字。
        """; // 查询改写系统提示词

    /**
     * 改写查询：调用 LLM 将原始查询扩展为主查询 + 子查询 + 关键词
     */
    public QueryExpansion rewrite(String query) {
        try {
            String model = configService.get("models.query-rewriter.model", "qwen-plus"); // 获取模型配置
            double temperature = configService.getDouble("models.query-rewriter.temperature", 0.2); // 获取温度配置
            int maxTokens = configService.getInt("models.query-rewriter.max-tokens", 512); // 获取最大 token 配置
            String result = llmClient.chat(model, temperature, maxTokens, // 调用 LLM 进行改写
                List.of(LlmClient.systemMsg(SYSTEM_PROMPT), LlmClient.userMsg(query)));

            // 清理可能的 markdown 代码块包裹
            String json = result.strip()
                .replaceAll("^```json\\s*", "").replaceAll("^```\\s*", "").replaceAll("\\s*```$", ""); // 去除 markdown 围栏

            QueryExpansion expansion = objectMapper.readValue(json, QueryExpansion.class); // 反序列化为 QueryExpansion 对象
            log.debug("QueryRewriter expanded '{}' → main='{}', subs={}", query, expansion.getMainQuery(), expansion.getSubQueries()); // 记录改写结果
            return expansion; // 返回改写结果
        } catch (Exception e) {
            log.warn("QueryRewriter failed for '{}': {}. Using fallback.", query, e.getMessage()); // 改写失败时记录警告
            QueryExpansion fallback = new QueryExpansion(); // 创建降级结果
            fallback.setMainQuery(query); // 主查询使用原始问题
            fallback.setSubQueries(List.of()); // 子查询为空
            fallback.setKeywords(List.of()); // 关键词为空
            return fallback; // 返回降级结果
        }
    }
}
