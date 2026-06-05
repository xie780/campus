package com.simon.campus.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.simon.campus.common.BizException;
import com.simon.campus.mapper.ChatMessageMapper;
import com.simon.campus.mapper.ChatSessionMapper;
import com.simon.campus.mapper.HumanTicketMapper;
import com.simon.campus.model.entity.ChatMessage;
import com.simon.campus.model.entity.ChatSession;
import com.simon.campus.model.entity.HumanTicket;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 人工转接服务：管理人工客服工单的创建、消息追加和教师回复
 */
@Service
@RequiredArgsConstructor
public class HumanHandoffService {

    private static final List<String> OPEN_STATUSES = List.of("PENDING", "HANDLING"); // 工单开放状态列表

    private final HumanTicketMapper ticketMapper; // 工单 Mapper
    private final ChatMessageMapper chatMessageMapper; // 消息 Mapper
    private final ChatSessionMapper chatSessionMapper; // 会话 Mapper

    /**
     * 查找指定会话的未关闭工单
     */
    public HumanTicket findOpenTicket(String sessionId) {
        if (!StringUtils.hasText(sessionId)) return null; // 会话 ID 为空返回 null
        return ticketMapper.selectOne(new LambdaQueryWrapper<HumanTicket>() // 查询最近的开放工单
            .eq(HumanTicket::getSessionId, sessionId)
            .in(HumanTicket::getStatus, OPEN_STATUSES)
            .orderByDesc(HumanTicket::getCreatedAt)
            .last("LIMIT 1"));
    }

    /**
     * 请求人工转接（默认追加通知消息）
     */
    public HumanTicket requestHandoff(String sessionId, Long userId, String summary, String urgency) {
        return requestHandoff(sessionId, userId, summary, urgency, true); // 委托给完整方法
    }

    /**
     * 请求人工转接：创建工单并追加通知消息
     */
    public HumanTicket requestHandoff(String sessionId, Long userId, String summary, String urgency, boolean appendNoticeMessages) {
        if (!StringUtils.hasText(sessionId)) throw new BizException("会话不存在，无法转人工"); // 校验会话 ID
        HumanTicket existing = findOpenTicket(sessionId); // 查找已有开放工单
        if (existing != null) return existing; // 已有则直接返回

        ensureSession(sessionId, userId, "人工服务请求"); // 确保会话存在

        String content = StringUtils.hasText(summary) ? summary : "申请转人工"; // 处理摘要文本
        if (appendNoticeMessages) { // 追加通知消息
            appendMessage(sessionId, userId, "user", content, "HUMAN_HANDOFF"); // 用户消息
            appendMessage(sessionId, userId, "assistant", // 助手通知消息
                "已为你转接人工老师。你可以继续在这里补充问题，老师会看到当前会话记录并回复。",
                "HUMAN_HANDOFF");
            touchSession(sessionId, 2); // 更新会话消息计数
        }

        HumanTicket ticket = new HumanTicket(); // 创建工单
        ticket.setTicketNo("TK" + System.currentTimeMillis()); // 生成工单编号
        ticket.setSessionId(sessionId); // 关联会话
        ticket.setUserId(userId != null ? userId : 0L); // 设置用户 ID
        ticket.setSubject(content.length() > 100 ? content.substring(0, 100) : content); // 截取主题
        ticket.setIssueType("人工服务"); // 问题类型
        ticket.setAiSummary("用户申请转人工。老师可查看该会话中的学生与 AI 历史记录，并在同一会话回复。"); // AI 摘要
        ticket.setUrgency(normalizeUrgency(urgency)); // 标准化紧急程度
        ticket.setStatus("PENDING"); // 初始状态为待处理
        ticket.setCreatedAt(LocalDateTime.now()); // 设置创建时间
        ticket.setUpdatedAt(LocalDateTime.now()); // 设置更新时间
        ticketMapper.insert(ticket); // 插入工单
        return ticket; // 返回工单
    }

    /**
     * 追加学生消息到人工转接会话
     */
    public void appendStudentMessage(String sessionId, Long userId, String content) {
        appendMessage(sessionId, userId, "user", content, "HUMAN_HANDOFF"); // 追加用户消息
        touchSession(sessionId, 1); // 更新会话消息计数
        HumanTicket ticket = findOpenTicket(sessionId); // 查找开放工单
        if (ticket != null) { // 更新工单时间
            ticketMapper.update(null, new UpdateWrapper<HumanTicket>()
                .eq("id", ticket.getId())
                .set("updated_at", LocalDateTime.now()));
        }
    }

    /**
     * 教师回复工单
     */
    public ChatMessage reply(Long ticketId, Long teacherId, String content) {
        if (!StringUtils.hasText(content)) throw new BizException("回复内容不能为空"); // 校验内容非空
        HumanTicket ticket = ticketMapper.selectById(ticketId); // 查询工单
        if (ticket == null) throw new BizException(404, "工单不存在"); // 工单不存在

        ChatMessage message = appendMessage(ticket.getSessionId(), teacherId, "teacher", content, "HUMAN_HANDOFF"); // 追加教师消息
        touchSession(ticket.getSessionId(), 1); // 更新会话消息计数
        ticketMapper.update(null, new UpdateWrapper<HumanTicket>() // 更新工单状态为处理中
            .eq("id", ticketId)
            .set("status", "HANDLING")
            .set("updated_at", LocalDateTime.now()));
        return message; // 返回回复消息
    }

    /**
     * 确保会话存在：若不存在则创建
     */
    private void ensureSession(String sessionId, Long userId, String title) {
        if (chatSessionMapper.selectById(sessionId) != null) return; // 会话已存在则跳过
        ChatSession session = new ChatSession(); // 创建新会话
        session.setSessionId(sessionId); // 设置会话 ID
        session.setUserId(userId != null ? userId : 0L); // 设置用户 ID
        session.setTitle(title); // 设置标题
        session.setStatus("ACTIVE"); // 设置状态为活跃
        session.setMessageCount(0); // 初始消息数为 0
        session.setCreatedAt(LocalDateTime.now()); // 设置创建时间
        session.setUpdatedAt(LocalDateTime.now()); // 设置更新时间
        chatSessionMapper.insert(session); // 插入会话
    }

    /**
     * 追加消息到会话
     */
    private ChatMessage appendMessage(String sessionId, Long userId, String role, String content, String intent) {
        ChatMessage message = new ChatMessage(); // 创建消息
        message.setSessionId(sessionId); // 关联会话
        message.setUserId(userId); // 设置用户 ID
        message.setRole(role); // 设置角色
        message.setContent(content); // 设置内容
        message.setIntent(intent); // 设置意图
        message.setCreatedAt(LocalDateTime.now()); // 设置创建时间
        chatMessageMapper.insert(message); // 插入消息
        return message; // 返回消息
    }

    /**
     * 更新会话消息计数和更新时间
     */
    private void touchSession(String sessionId, int addedMessages) {
        chatSessionMapper.update(null, new UpdateWrapper<ChatSession>() // 原子更新消息计数
            .eq("session_id", sessionId)
            .setSql("message_count = message_count + " + addedMessages + ", updated_at = NOW()"));
    }

    /**
     * 标准化紧急程度
     */
    private String normalizeUrgency(String urgency) {
        if (urgency == null) return "MEDIUM"; // 默认中等
        return switch (urgency.toLowerCase()) {
            case "high", "高", "紧急" -> "HIGH"; // 高
            case "low", "低" -> "LOW"; // 低
            default -> "MEDIUM"; // 中等
        };
    }
}
