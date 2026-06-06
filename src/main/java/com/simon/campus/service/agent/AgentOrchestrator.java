package com.simon.campus.service.agent;

import com.simon.campus.model.dto.QueryExpansion;
import com.simon.campus.model.dto.RecallCandidate;
import com.simon.campus.model.entity.AgentLog;
import com.simon.campus.service.rag.*;
import com.simon.campus.service.ingest.VisibilityPolicy;
import com.simon.campus.service.tool.SpringAiToolCaller;
import com.simon.campus.service.tool.ToolCaller;
import com.simon.campus.service.tool.ToolResult;
import com.simon.campus.session.SessionContext;
import com.simon.campus.session.SessionManager;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * Agent 编排器：协调整个对话处理流程，包括上下文合并、意图路由、RAG 管线、工具调用和人工转接
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestrator {

    private final SessionManager sessionManager; // 会话管理器
    private final ContextMerger contextMerger; // 上下文合并器
    private final QueryRewriter queryRewriter; // 查询改写器
    private final MultiRouteRecaller recaller; // 多路召回器
    private final Reranker reranker; // 重排序器
    private final ParentChildContextAssembler assembler; // 父子上下文组装器
    private final RagGenerator ragGenerator; // RAG 生成器
    private final IntentRouter intentRouter; // 意图路由器
    private final AgentLogService agentLogService; // Agent 日志服务
    private final ToolCaller toolCaller; // 传统工具调用器（兼容保留）
    private final SpringAiToolCaller springAiToolCaller; // Spring AI 工具调用器

    /**
     * 编排结果：包含回答、意图、来源引用、FAQ 短路标记和工具调用结果
     */
    @Data
    @AllArgsConstructor
    public static class OrchestrationResult {
        private final String answer; // 最终回答
        private final String intent; // 识别的意图
        private final List<ParentChildContextAssembler.SourceRef> sourceRefs; // 来源引用列表
        private final boolean faqShortCircuited; // 是否被 FAQ 短路
        /** Non-null when an ACADEMIC_TOOL was invoked */
        private final ToolResult toolResult; // 工具调用结果（非空时表示调用了学术工具）
    }

    /**
     * 流式处理用户查询：执行完整的 Agent 编排流程
     */
    public OrchestrationResult handleStream(
            String rawQuery, String sessionId,
            Long userId, String username, String role,
            Consumer<String> onToken) {

        long t0 = System.currentTimeMillis(); // 记录起始时间
        SessionContext session = sessionManager.getOrCreate(sessionId, userId, username, role); // 获取或创建会话
        log.info("[AGENT_FLOW] session={} step=start role={} rawQuery={}",
            session.getSessionId(), role, abbreviate(rawQuery));

        AgentLog logEntry = new AgentLog(); // 创建日志记录
        logEntry.setSessionId(session.getSessionId()); // 设置会话 ID
        logEntry.setUserId(userId); // 设置用户 ID
        logEntry.setUserQuery(rawQuery); // 设置原始查询

        // Stage 1: Context merge
        long s1 = System.currentTimeMillis(); // 记录上下文合并开始时间
        String mergedQuery = contextMerger.merge(rawQuery, session); // 合并上下文
        logEntry.setStage1Ms((int)(System.currentTimeMillis() - s1)); // 记录耗时
        log.info("[AGENT_FLOW] session={} step=context_merge costMs={} mergedQuery={}",
            session.getSessionId(), logEntry.getStage1Ms(), abbreviate(mergedQuery));

        // Intent routing
        String intent = intentRouter.route(mergedQuery); // 路由意图
        logEntry.setIntent(intent); // 记录意图
        session.setCurrentIntent(intent); // 设置会话当前意图
        log.info("[AGENT_FLOW] session={} step=intent_route intent={}", session.getSessionId(), intent);

        String answer = ""; // 初始化回答
        List<ParentChildContextAssembler.SourceRef> sourceRefs = List.of(); // 初始化来源引用
        boolean faqHit = false; // FAQ 命中标记
        ToolResult toolResult = null; // 工具调用结果

        try {
            if ("HUMAN".equals(intent)) { // 意图为人工转接
                log.info("[AGENT_FLOW] session={} step=human_transfer", session.getSessionId());
                answer = handleHumanTransfer(onToken, session); // 处理人工转接
            } else if ("CHITCHAT".equals(intent)) { // 意图为闲聊
                log.info("[AGENT_FLOW] session={} step=chitchat_generate", session.getSessionId());
                answer = ragGenerator.chitchatStream(mergedQuery, session, onToken); // 闲聊生成
            } else if ("ACADEMIC_TOOL".equals(intent)) { // 意图为学术工具调用
                log.info("[AGENT_FLOW] session={} step=tool_call", session.getSessionId());
                // 优先使用 Spring AI 工具调用
                SpringAiToolCaller.ToolCallResult tcr = springAiToolCaller.call(mergedQuery, session, onToken);
                answer = tcr.answer(); // 获取回答
                toolResult = tcr.toolResult(); // 获取工具结果
                if (toolResult != null) logEntry.setHitDocs("TOOL:" + toolResult.getToolName()); // 记录命中文档
            } else {
                // POLICY_QA / DOC_SEARCH → RAG pipeline
                PipelineResult pr = runRagPipeline(mergedQuery, session, logEntry, onToken); // 执行 RAG 管线
                answer = pr.answer(); // 获取回答
                sourceRefs = pr.sourceRefs(); // 获取来源引用
                faqHit = "FAQ".equals(logEntry.getHitDocs()); // 判断是否 FAQ 短路
            }
        } catch (Exception e) { // 异常处理
            log.error("Pipeline failed: {}", e.getMessage(), e); // 记录错误
            answer = "抱歉，处理您的请求时出现错误，请稍后重试。"; // 返回默认错误提示
            try { onToken.accept(answer); } catch (Exception ignored) {} // 推送错误提示
        }

        session.addMessage("user", rawQuery); // 添加用户消息到会话
        session.addMessage("assistant", answer); // 添加助手消息到会话
        sessionManager.save(session); // 保存会话

        logEntry.setTotalMs((int)(System.currentTimeMillis() - t0)); // 记录总耗时
        agentLogService.save(logEntry); // 异步保存日志

        log.info("[AGENT_FLOW] session={} step=done intent={} totalMs={} answer={}",
            session.getSessionId(), intent, logEntry.getTotalMs(), abbreviate(answer));
        return new OrchestrationResult(answer, intent, sourceRefs, faqHit, toolResult); // 返回编排结果
    }

    /**
     * RAG 管线结果记录
     */
    private record PipelineResult(
        String answer,
        List<ParentChildContextAssembler.SourceRef> sourceRefs
    ) {}

    /**
     * 执行 RAG 管线：查询改写 → 多路召回 → 重排序 → 上下文组装 → 生成回答
     */
    private PipelineResult runRagPipeline(String mergedQuery, SessionContext session,
                                           AgentLog logEntry, Consumer<String> onToken) throws Exception {
        int userAccessLevel = accessLevelFor(session.getRole()); // 获取用户可见级别

        long s2 = System.currentTimeMillis(); // 记录查询改写开始时间
        QueryExpansion expansion = queryRewriter.rewrite(mergedQuery); // 查询改写
        logEntry.setRewrittenQuery(expansion.getMainQuery()); // 记录改写后的主查询
        logEntry.setStage2Ms((int)(System.currentTimeMillis() - s2)); // 记录耗时
        log.info("[AGENT_FLOW] session={} step=query_rewrite costMs={} mainQuery={} subQueries={} keywords={}",
            session.getSessionId(), logEntry.getStage2Ms(), abbreviate(expansion.getMainQuery()),
            expansion.getSubQueries(), expansion.getKeywords());

        long s3 = System.currentTimeMillis(); // 记录召回开始时间
        MultiRouteRecaller.RecallResult recallResult = recaller.recall(expansion, userAccessLevel); // 多路召回
        logEntry.setStage3Ms((int)(System.currentTimeMillis() - s3)); // 记录耗时
        log.info("[AGENT_FLOW] session={} step=recall costMs={} faqShortCircuited={} candidates={}",
            session.getSessionId(), logEntry.getStage3Ms(), recallResult.faqShortCircuited(),
            recallResult.candidates().size());

        if (recallResult.faqShortCircuited()) { // FAQ 短路命中
            logEntry.setRecallCount(0); // 召回数为 0
            logEntry.setHitDocs("FAQ"); // 标记为 FAQ 命中
            String ans = recallResult.faqAnswer(); // 获取 FAQ 回答
            onToken.accept(ans); // 推送回答
            log.info("[AGENT_FLOW] session={} step=faq_short_circuit answer={}",
                session.getSessionId(), abbreviate(ans));
            return new PipelineResult(ans, List.of()); // 返回 FAQ 短路结果
        }

        List<RecallCandidate> candidates = recallResult.candidates(); // 获取召回候选
        logEntry.setRecallCount(candidates.size()); // 记录召回数量

        long s4 = System.currentTimeMillis(); // 记录重排序开始时间
        List<RecallCandidate> reranked = reranker.rerank(expansion.getMainQuery(), candidates); // 重排序
        logEntry.setStage4Ms((int)(System.currentTimeMillis() - s4)); // 记录耗时
        logEntry.setRerankCount(reranked.size()); // 记录重排序后数量
        log.info("[AGENT_FLOW] session={} step=rerank costMs={} in={} out={}",
            session.getSessionId(), logEntry.getStage4Ms(), candidates.size(), reranked.size());

        long s5 = System.currentTimeMillis(); // 记录上下文组装开始时间
        ParentChildContextAssembler.AssembledContext ctx = assembler.assemble(reranked); // 组装上下文
        logEntry.setStage5Ms((int)(System.currentTimeMillis() - s5)); // 记录耗时
        logEntry.setParentCount(ctx.getSourceRefs().size()); // 记录父分块数
        log.info("[AGENT_FLOW] session={} step=assemble_context costMs={} parents={} context={}",
            session.getSessionId(), logEntry.getStage5Ms(), ctx.getSourceRefs().size(),
            abbreviate(ctx.getContextText()));

        if (!ctx.getSourceRefs().isEmpty()) { // 记录命中文档标题
            String docs = ctx.getSourceRefs().stream()
                .map(ParentChildContextAssembler.SourceRef::getDocTitle)
                .distinct().reduce((a, b) -> a + "|" + b).orElse("");
            logEntry.setHitDocs(docs); // 设置命中文档
        }

        long s6 = System.currentTimeMillis(); // 记录生成开始时间
        String answer = ragGenerator.generateStream( // 流式生成回答
            expansion.getMainQuery(), ctx.getContextText(), session, onToken);
        logEntry.setStage6Ms((int)(System.currentTimeMillis() - s6)); // 记录耗时
        log.info("[AGENT_FLOW] session={} step=rag_generate costMs={} answer={}",
            session.getSessionId(), logEntry.getStage6Ms(), abbreviate(answer));

        return new PipelineResult(answer, ctx.getSourceRefs()); // 返回管线结果
    }

    /**
     * 处理人工转接：设置会话状态并返回提示消息
     */
    private String handleHumanTransfer(Consumer<String> onToken, SessionContext session) {
        session.setStatus("HUMAN_PENDING"); // 设置会话状态为等待人工
        String msg = "好的，我已为您发起人工客服请求，请稍候，工作人员将尽快与您联系。"; // 提示消息
        onToken.accept(msg); // 推送消息
        return msg; // 返回消息
    }

    /**
     * 根据角色获取可见级别
     */
    private int accessLevelFor(String role) {
        if (role == null) return VisibilityPolicy.STUDENT; // 角色为空默认学生级别
        return switch (role.toUpperCase()) {
            case "ADMIN", "TEACHER" -> VisibilityPolicy.TEACHER; // 管理员和教师为教师级别
            default -> VisibilityPolicy.STUDENT; // 其他为学生级别
        };
    }

    /**
     * 截断过长的字符串用于日志输出
     */
    private String abbreviate(String value) {
        if (value == null) return ""; // 空值返回空字符串
        String normalized = value.replaceAll("\\s+", " ").strip(); // 规范化空白字符
        int max = 2000; // 最大长度
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]"; // 超长截断
    }
}
