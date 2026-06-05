package com.simon.campus.service.admin;

import com.simon.campus.mapper.DashboardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 仪表盘服务：聚合系统运营统计数据，包括核心指标、趋势图表、意图分布和热门查询
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardMapper dashboardMapper; // 仪表盘 Mapper

    /**
     * 获取指定天数内的仪表盘数据
     */
    public Map<String, Object> getDashboard(int days) {
        int normalizedDays = Math.max(1, Math.min(days, 365)); // 限制天数范围在 1-365
        LocalDateTime now = LocalDateTime.now(); // 当前时间
        LocalDateTime since = now.minusDays(normalizedDays); // 统计起始时间
        LocalDateTime prevSince = since.minusDays(normalizedDays); // 上期起始时间（用于计算趋势）
        Map<String, Object> result = new LinkedHashMap<>(); // 结果容器

        // ── Core metrics ──────────────────────────────────────────────────────
        long totalRequests = dashboardMapper.countTotalRequests(since); // 总请求数
        long previousTotalRequests = dashboardMapper.countTotalRequestsBetween(prevSince, since); // 上期总请求数
        long faqHits = dashboardMapper.countFaqHits(since); // FAQ 命中数
        double faqHitRate = totalRequests > 0 ? Math.round((double) faqHits / totalRequests * 1000) / 10.0 : 0; // FAQ 命中率
        long knowledgeHits = dashboardMapper.countKnowledgeHits(since); // 知识库命中数
        long knowledgeReferences = dashboardMapper.countKnowledgeReferences(since); // 知识库引用数
        long assistantMessages = dashboardMapper.countAssistantMessages(since); // 助手消息数
        long assistantMessagesWithRefs = dashboardMapper.countAssistantMessagesWithRefs(since); // 带引用的助手消息数
        long humanRequests = dashboardMapper.countHumanRequests(since); // 人工请求次数
        long humanTickets = dashboardMapper.countHumanTickets(since); // 人工工单数
        long previousHumanRequests = dashboardMapper.countHumanRequestsBetween(prevSince, since); // 上期人工请求数
        long todaySessions = dashboardMapper.countSessionsToday(); // 今日会话数
        long yesterdaySessions = dashboardMapper.countSessionsBetween( // 昨日会话数
            now.minusDays(1).toLocalDate().atStartOfDay(), now.toLocalDate().atStartOfDay());

        Double avgMs = dashboardMapper.selectAvgResponseMs(since); // 平均响应时间
        Double previousAvgMs = dashboardMapper.selectAvgResponseMsBetween(prevSince, since); // 上期平均响应时间
        Double avgRating = dashboardMapper.selectAvgRating(since); // 平均评分

        double knowledgeHitRate = totalRequests > 0 ? round1((double) knowledgeHits / totalRequests * 100) : 0; // 知识库命中率
        double citationRate = assistantMessages > 0 ? round1((double) assistantMessagesWithRefs / assistantMessages * 100) : 0; // 引用率
        double transferRate = totalRequests > 0 ? round1((double) Math.max(humanRequests, humanTickets) / totalRequests * 100) : 0; // 转人工率
        double previousTransferRate = previousTotalRequests > 0 ? round1((double) previousHumanRequests / previousTotalRequests * 100) : 0; // 上期转人工率
        double satisfaction = avgRating != null && avgRating > 0 ? round1(avgRating / 5.0 * 100) : 0; // 满意度

        Map<String, Object> metrics = new LinkedHashMap<>(); // 核心指标容器
        metrics.put("sessionsToday",  todaySessions); // 今日会话数
        metrics.put("messagesToday",  dashboardMapper.countMessagesToday()); // 今日消息数
        metrics.put("pendingTickets", dashboardMapper.countPendingTickets()); // 待处理工单数
        metrics.put("readyDocs",      dashboardMapper.countReadyDocs()); // 就绪文档数
        metrics.put("activeFaqs",     dashboardMapper.countActiveFaqs()); // 活跃 FAQ 数
        metrics.put("faqHitRate",     faqHitRate); // FAQ 命中率
        metrics.put("avgResponseMs",  avgMs != null ? Math.round(avgMs) : 0); // 平均响应时间
        metrics.put("totalRequests",  totalRequests); // 总请求数
        metrics.put("satisfaction",   satisfaction); // 满意度
        metrics.put("transferRate",   transferRate); // 转人工率
        metrics.put("knowledgeHitRate", knowledgeHitRate); // 知识库命中率
        metrics.put("citationRate", citationRate); // 引用率
        metrics.put("knowledgeHitCount", knowledgeHits); // 知识库命中数
        metrics.put("knowledgeReferenceCount", knowledgeReferences); // 知识库引用数
        metrics.put("totalRequestsTrend", trendPercent(totalRequests, previousTotalRequests)); // 总请求趋势
        metrics.put("sessionsTodayTrend", trendPercent(todaySessions, yesterdaySessions)); // 今日会话趋势
        metrics.put("transferRateTrend", round1(transferRate - previousTransferRate)); // 转人工率趋势
        metrics.put("avgResponseMsTrend", previousAvgMs != null ? Math.round(avgMs != null ? avgMs - previousAvgMs : -previousAvgMs) : 0); // 响应时间趋势
        result.put("metrics", metrics); // 放入核心指标

        // ── Trend charts ──────────────────────────────────────────────────────
        result.put("sessionTrend", dashboardMapper.selectSessionTrend(since)); // 会话趋势
        result.put("messageTrend", dashboardMapper.selectMessageTrend(since)); // 消息趋势
        result.put("uniqueUserTrend", dashboardMapper.selectUniqueUserTrend(since)); // 独立用户趋势

        // ── Intent distribution ───────────────────────────────────────────────
        result.put("intentDistribution", dashboardMapper.selectIntentDistribution(since)); // 意图分布
        result.put("hotCategories", dashboardMapper.selectHotCategories(since)); // 热门分类

        // ── Top queries ───────────────────────────────────────────────────────
        result.put("topQueries", dashboardMapper.selectTopQueries(since, 10)); // 热门查询

        // ── Tool call counts ──────────────────────────────────────────────────
        result.put("toolCalls", dashboardMapper.selectToolCalls(since)); // 工具调用统计
        result.put("humanTakeoverTrend", dashboardMapper.selectHumanTakeoverTrend(since)); // 人工接管趋势

        return result; // 返回完整仪表盘数据
    }

    /**
     * 四舍五入保留一位小数
     */
    private double round1(double value) {
        return Math.round(value * 10) / 10.0; // 保留一位小数
    }

    /**
     * 计算趋势百分比（当前值相对上期值的变化率）
     */
    private double trendPercent(long current, long previous) {
        if (previous <= 0) { // 上期值为零
            return current > 0 ? 100.0 : 0.0; // 当前有值返回 100%，否则 0%
        }
        return round1((double) (current - previous) / previous * 100); // 计算变化率
    }
}
