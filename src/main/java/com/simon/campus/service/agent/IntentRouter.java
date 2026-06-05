package com.simon.campus.service.agent;

import com.simon.campus.common.LlmClient;
import com.simon.campus.service.admin.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 意图路由器：使用关键词快速匹配或 LLM 分类将用户查询路由到五种意图之一
 * 返回意图类型：POLICY_QA / DOC_SEARCH / ACADEMIC_TOOL / CHITCHAT / HUMAN
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IntentRouter {

    private final LlmClient llmClient; // LLM 客户端
    private final SystemConfigService configService; // 系统配置服务

    private static final String SYSTEM = """
        你是一个意图分类器，将用户问题分类为以下5种之一：
        - POLICY_QA: 询问学校政策、规定、规则（如转专业、退学、成绩、考试等）
        - DOC_SEARCH: 通用文档搜索和信息查询
        - ACADEMIC_TOOL: 查询校历、选课时间、考试安排、部门联系方式等结构化信息
        - CHITCHAT: 闲聊、问候、与校园无关的对话
        - HUMAN: 明确要求转人工客服、有紧急投诉或情绪激动

        只输出一个标签，不要有任何解释。
        """; // LLM 意图分类系统提示词

    private static final List<String> ACADEMIC_KEYWORDS = List.of( // 学术工具关键词列表
        "校历", "选课", "考试安排", "联系方式", "电话", "邮箱", "办公室", "开学", "放假", "学期");
    private static final List<String> HUMAN_KEYWORDS = List.of( // 人工转接关键词列表
        "找老师", "转人工", "人工客服", "真人", "投诉", "举报", "紧急");
    private static final List<String> CHITCHAT_KEYWORDS = List.of( // 闲聊关键词列表
        "你好", "hi", "hello", "谢谢", "再见", "你是谁", "介绍");

    /**
     * 对用户查询进行意图路由：先尝试关键词快速匹配，再使用 LLM 分类
     */
    public String route(String query) {
        String lower = query.toLowerCase(); // 转小写用于关键词匹配

        // Keyword fast path
        if (HUMAN_KEYWORDS.stream().anyMatch(lower::contains)) return "HUMAN"; // 匹配人工转接关键词
        if (ACADEMIC_KEYWORDS.stream().anyMatch(lower::contains)) return "ACADEMIC_TOOL"; // 匹配学术工具关键词
        if (CHITCHAT_KEYWORDS.stream().anyMatch(lower::contains) && query.length() < 20) return "CHITCHAT"; // 匹配闲聊关键词（短文本）

        try {
            String model = configService.get("models.intent-router.model", "qwen-turbo"); // 获取意图路由模型
            double temperature = configService.getDouble("models.intent-router.temperature", 0.1); // 获取温度参数
            int maxTokens = configService.getInt("models.intent-router.max-tokens", 16); // 获取最大 token 数
            String result = llmClient.chat(model, temperature, maxTokens, // 调用 LLM 分类
                List.of(LlmClient.systemMsg(SYSTEM), LlmClient.userMsg(query)));
            String intent = result.strip().toUpperCase(); // 提取并标准化意图标签
            if (isValidIntent(intent)) { // 校验意图是否合法
                log.debug("Intent '{}' → {}", query, intent);
                return intent; // 返回合法意图
            }
            return "POLICY_QA"; // 非法意图默认为政策问答
        } catch (Exception e) {
            log.warn("IntentRouter failed: {}, defaulting to POLICY_QA", e.getMessage()); // LLM 调用失败
            return "POLICY_QA"; // 默认为政策问答
        }
    }

    /**
     * 校验意图标签是否为合法值
     */
    private boolean isValidIntent(String s) {
        return switch (s) {
            case "POLICY_QA", "DOC_SEARCH", "ACADEMIC_TOOL", "CHITCHAT", "HUMAN" -> true; // 合法意图
            default -> false; // 其他为非法
        };
    }
}
