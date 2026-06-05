package com.simon.campus.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘 Mapper：提供系统运营数据统计查询，包括意图分布、会话趋势、
 * 人工接管率、热门查询、工具调用、知识命中率等
 */
@Mapper
public interface DashboardMapper {

    /**
     * 查询意图分布统计
     */
    @Select("SELECT intent, COUNT(*) AS cnt FROM agent_logs WHERE created_at >= #{since} GROUP BY intent ORDER BY cnt DESC")
    List<Map<String, Object>> selectIntentDistribution(@Param("since") LocalDateTime since);

    /**
     * 查询会话数趋势（按日统计）
     */
    @Select("SELECT DATE_FORMAT(created_at, '%m-%d') AS date_str, COUNT(*) AS cnt FROM chat_sessions WHERE created_at >= #{since} GROUP BY DATE_FORMAT(created_at, '%m-%d'), DATE(created_at) ORDER BY DATE(created_at)")
    List<Map<String, Object>> selectSessionTrend(@Param("since") LocalDateTime since);

    /**
     * 查询消息数趋势（按日统计，仅用户消息）
     */
    @Select("SELECT DATE_FORMAT(created_at, '%m-%d') AS date_str, COUNT(*) AS cnt FROM chat_messages WHERE role = 'user' AND created_at >= #{since} GROUP BY DATE_FORMAT(created_at, '%m-%d'), DATE(created_at) ORDER BY DATE(created_at)")
    List<Map<String, Object>> selectMessageTrend(@Param("since") LocalDateTime since);

    /**
     * 查询独立用户数趋势（按日统计）
     */
    @Select("SELECT DATE_FORMAT(s.created_at, '%m-%d') AS date_str, " +
            "COUNT(DISTINCT s.user_id) AS cnt " +
            "FROM chat_sessions s " +
            "WHERE s.created_at >= #{since} " +
            "GROUP BY DATE_FORMAT(s.created_at, '%m-%d'), DATE(s.created_at) " +
            "ORDER BY DATE(s.created_at)")
    List<Map<String, Object>> selectUniqueUserTrend(@Param("since") LocalDateTime since);

    /**
     * 查询人工接管趋势（按日统计工单数和接管率）
     */
    @Select("SELECT DATE_FORMAT(d.dt, '%m-%d') AS date_str, " +
            "COALESCE(t.ticket_count, 0) AS cnt, " +
            "ROUND(CASE WHEN COALESCE(s.session_count, 0) = 0 THEN 0 ELSE COALESCE(t.ticket_count, 0) / s.session_count * 100 END, 1) AS rate " +
            "FROM ( " +
            "  SELECT DATE(created_at) AS dt FROM chat_sessions WHERE created_at >= #{since} " +
            "  UNION SELECT DATE(created_at) AS dt FROM human_tickets WHERE created_at >= #{since} " +
            ") d " +
            "LEFT JOIN (SELECT DATE(created_at) AS dt, COUNT(*) AS session_count FROM chat_sessions WHERE created_at >= #{since} GROUP BY DATE(created_at)) s ON s.dt = d.dt " +
            "LEFT JOIN (SELECT DATE(created_at) AS dt, COUNT(*) AS ticket_count FROM human_tickets WHERE created_at >= #{since} GROUP BY DATE(created_at)) t ON t.dt = d.dt " +
            "ORDER BY d.dt")
    List<Map<String, Object>> selectHumanTakeoverTrend(@Param("since") LocalDateTime since);

    /**
     * 查询热门查询 Top N
     */
    @Select("SELECT user_query, COUNT(*) AS cnt FROM agent_logs WHERE created_at >= #{since} AND user_query IS NOT NULL GROUP BY user_query ORDER BY cnt DESC LIMIT #{n}")
    List<Map<String, Object>> selectTopQueries(@Param("since") LocalDateTime since, @Param("n") int n);

    /**
     * 查询工具调用统计
     */
    @Select("SELECT REPLACE(hit_docs, 'TOOL:', '') AS tool_name, COUNT(*) AS cnt FROM agent_logs WHERE hit_docs LIKE 'TOOL:%' AND created_at >= #{since} GROUP BY hit_docs ORDER BY cnt DESC")
    List<Map<String, Object>> selectToolCalls(@Param("since") LocalDateTime since);

    /**
     * 查询热门分类统计（将意图映射为中文名称）
     */
    @Select("SELECT CASE intent " +
            "WHEN 'ACADEMIC_TOOL' THEN '教务工具' " +
            "WHEN 'POLICY_QA' THEN '政策问答' " +
            "WHEN 'DOC_SEARCH' THEN '文档检索' " +
            "WHEN 'HUMAN' THEN '人工服务' " +
            "WHEN 'HUMAN_HANDOFF' THEN '人工服务' " +
            "WHEN 'CHITCHAT' THEN '闲聊咨询' " +
            "ELSE COALESCE(NULLIF(intent, ''), '其他') END AS name, " +
            "COUNT(*) AS cnt " +
            "FROM agent_logs " +
            "WHERE created_at >= #{since} " +
            "GROUP BY name " +
            "ORDER BY cnt DESC " +
            "LIMIT 6")
    List<Map<String, Object>> selectHotCategories(@Param("since") LocalDateTime since);

    /**
     * 查询平均响应耗时（ms）
     */
    @Select("SELECT COALESCE(AVG(total_ms), 0) FROM agent_logs WHERE created_at >= #{since} AND total_ms > 0")
    Double selectAvgResponseMs(@Param("since") LocalDateTime since);

    /**
     * 查询指定时间段的平均响应耗时（ms）
     */
    @Select("SELECT COALESCE(AVG(total_ms), 0) FROM agent_logs WHERE created_at >= #{start} AND created_at < #{end} AND total_ms > 0")
    Double selectAvgResponseMsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 统计 FAQ 命中次数
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE hit_docs = 'FAQ' AND created_at >= #{since}")
    long countFaqHits(@Param("since") LocalDateTime since);

    /**
     * 统计知识库命中次数（排除工具调用和 FAQ）
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE created_at >= #{since} AND hit_docs IS NOT NULL AND hit_docs <> '' AND hit_docs NOT LIKE 'TOOL:%'")
    long countKnowledgeHits(@Param("since") LocalDateTime since);

    /**
     * 统计知识库引用的父块总数
     */
    @Select("SELECT COALESCE(SUM(parent_count), 0) FROM agent_logs WHERE created_at >= #{since} AND parent_count > 0")
    long countKnowledgeReferences(@Param("since") LocalDateTime since);

    /**
     * 统计带引用来源的助手消息数
     */
    @Select("SELECT COUNT(*) FROM chat_messages WHERE role = 'assistant' AND created_at >= #{since} AND source_refs IS NOT NULL AND source_refs <> ''")
    long countAssistantMessagesWithRefs(@Param("since") LocalDateTime since);

    /**
     * 统计助手消息总数
     */
    @Select("SELECT COUNT(*) FROM chat_messages WHERE role = 'assistant' AND created_at >= #{since}")
    long countAssistantMessages(@Param("since") LocalDateTime since);

    /**
     * 统计总请求数
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE created_at >= #{since}")
    long countTotalRequests(@Param("since") LocalDateTime since);

    /**
     * 统计指定时间段的总请求数
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE created_at >= #{start} AND created_at < #{end}")
    long countTotalRequestsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 统计人工服务请求数
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE created_at >= #{since} AND (intent = 'HUMAN' OR intent = 'HUMAN_HANDOFF')")
    long countHumanRequests(@Param("since") LocalDateTime since);

    /**
     * 统计指定时间段的人工服务请求数
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE created_at >= #{start} AND created_at < #{end} AND (intent = 'HUMAN' OR intent = 'HUMAN_HANDOFF')")
    long countHumanRequestsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 统计人工工单总数
     */
    @Select("SELECT COUNT(*) FROM human_tickets WHERE created_at >= #{since}")
    long countHumanTickets(@Param("since") LocalDateTime since);

    /**
     * 查询人工工单平均评分
     */
    @Select("SELECT COALESCE(AVG(rating), 0) FROM human_tickets WHERE rating IS NOT NULL AND created_at >= #{since}")
    Double selectAvgRating(@Param("since") LocalDateTime since);

    /**
     * 统计今日会话数
     */
    @Select("SELECT COUNT(*) FROM chat_sessions WHERE DATE(created_at) = CURDATE()")
    long countSessionsToday();

    /**
     * 统计指定时间段的会话数
     */
    @Select("SELECT COUNT(*) FROM chat_sessions WHERE created_at >= #{start} AND created_at < #{end}")
    long countSessionsBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 统计今日消息数
     */
    @Select("SELECT COUNT(*) FROM chat_messages WHERE DATE(created_at) = CURDATE()")
    long countMessagesToday();

    /**
     * 统计待处理工单数
     */
    @Select("SELECT COUNT(*) FROM human_tickets WHERE status = 'PENDING'")
    long countPendingTickets();

    /**
     * 统计已就绪文档数
     */
    @Select("SELECT COUNT(*) FROM knowledge_docs WHERE status = 'READY'")
    long countReadyDocs();

    /**
     * 统计已启用的 FAQ 数量
     */
    @Select("SELECT COUNT(*) FROM faq_pairs WHERE enabled = 1")
    long countActiveFaqs();
}
