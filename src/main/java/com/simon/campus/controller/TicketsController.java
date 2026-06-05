package com.simon.campus.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.simon.campus.common.R;
import com.simon.campus.mapper.ChatMessageMapper;
import com.simon.campus.mapper.HumanTicketMapper;
import com.simon.campus.model.entity.ChatMessage;
import com.simon.campus.model.entity.HumanTicket;
import com.simon.campus.service.admin.HumanHandoffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工单控制器：处理人工客服工单的查询、回复、状态流转及评价
 */
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
@Slf4j
public class TicketsController {

    private final HumanTicketMapper ticketMapper; // 工单 Mapper
    private final ChatMessageMapper chatMessageMapper; // 消息 Mapper
    private final HumanHandoffService handoffService; // 人工转接服务
    private final SimpMessagingTemplate messagingTemplate; // WebSocket 消息推送模板

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * 查询工单列表（支持状态、紧急程度、问题类型过滤）
     */
    @GetMapping
    public R<List<HumanTicket>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String urgency,
            @RequestParam(required = false) String issueType) {
        LambdaQueryWrapper<HumanTicket> qw = new LambdaQueryWrapper<HumanTicket>() // 构建查询条件
            .orderByDesc(HumanTicket::getCreatedAt); // 按创建时间倒序
        if (StringUtils.hasText(status))    qw.eq(HumanTicket::getStatus, status); // 按状态过滤
        if (StringUtils.hasText(urgency))   qw.eq(HumanTicket::getUrgency, urgency); // 按紧急程度过滤
        if (StringUtils.hasText(issueType)) qw.eq(HumanTicket::getIssueType, issueType); // 按问题类型过滤
        return R.ok(ticketMapper.selectList(qw)); // 返回工单列表
    }

    /**
     * 获取各状态工单数量统计
     */
    @GetMapping("/stats")
    public R<Map<String, Long>> stats() {
        return R.ok(Map.of( // 返回各状态工单计数
            "PENDING",  countByStatus("PENDING"), // 待处理
            "HANDLING", countByStatus("HANDLING"), // 处理中
            "RESOLVED", countByStatus("RESOLVED"), // 已解决
            "CLOSED",   countByStatus("CLOSED") // 已关闭
        ));
    }

    /**
     * 根据 ID 获取工单详情
     */
    @GetMapping("/{id}")
    public R<HumanTicket> getById(@PathVariable Long id) {
        return R.ok(ticketMapper.selectById(id)); // 返回指定 ID 的工单
    }

    /**
     * 获取工单关联的消息列表
     */
    @GetMapping("/{id}/messages")
    public R<List<ChatMessage>> getMessages(@PathVariable Long id) {
        HumanTicket ticket = ticketMapper.selectById(id); // 查询工单
        if (ticket == null) return R.ok(List.of()); // 工单不存在返回空列表
        return R.ok(chatMessageMapper.selectList( // 查询该工单关联会话的消息
            new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, ticket.getSessionId())
                .orderByAsc(ChatMessage::getId))); // 按消息 ID 升序
    }

    // ── State transitions ─────────────────────────────────────────────────────

    /**
     * 回复工单（仅教师和管理员）
     */
    @PostMapping("/{id}/reply")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<ChatMessage> reply(@PathVariable Long id, @RequestBody ReplyRequest request) {
        ChatMessage message = handoffService.reply(id, currentUserId(), request.content()); // 调用服务回复工单
        pushUpdate(id, "HANDLING"); // 推送 WebSocket 更新
        return R.ok(message); // 返回回复消息
    }

    /**
     * 将工单标记为已解决（仅教师和管理员）
     */
    @PutMapping("/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> resolve(@PathVariable Long id) {
        ticketMapper.update(null, new LambdaUpdateWrapper<HumanTicket>() // 更新工单状态为已解决
            .eq(HumanTicket::getId, id)
            .set(HumanTicket::getStatus, "RESOLVED")
            .set(HumanTicket::getUpdatedAt, LocalDateTime.now()));
        pushUpdate(id, "RESOLVED"); // 推送 WebSocket 更新
        return R.ok(null);
    }

    /**
     * 关闭工单（仅教师和管理员）
     */
    @PutMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> close(@PathVariable Long id) {
        ticketMapper.update(null, new LambdaUpdateWrapper<HumanTicket>() // 更新工单状态为已关闭
            .eq(HumanTicket::getId, id)
            .set(HumanTicket::getStatus, "CLOSED")
            .set(HumanTicket::getUpdatedAt, LocalDateTime.now()));
        pushUpdate(id, "CLOSED"); // 推送 WebSocket 更新
        return R.ok(null);
    }

    /**
     * 对工单进行评分（1-5 分）
     */
    @PutMapping("/{id}/rate")
    public R<Void> rate(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer rating = body.get("rating"); // 获取评分
        if (rating == null || rating < 1 || rating > 5) return R.ok(null); // 评分无效则忽略
        ticketMapper.update(null, new LambdaUpdateWrapper<HumanTicket>() // 更新工单评分
            .eq(HumanTicket::getId, id)
            .set(HumanTicket::getRating, rating)
            .set(HumanTicket::getUpdatedAt, LocalDateTime.now()));
        return R.ok(null);
    }

    /**
     * 删除工单（仅管理员）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> delete(@PathVariable Long id) {
        ticketMapper.deleteById(id); // 删除指定工单
        return R.ok(null);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * 按状态统计工单数量
     */
    private long countByStatus(String status) {
        return ticketMapper.selectCount(new LambdaQueryWrapper<HumanTicket>() // 查询指定状态的工单数
            .eq(HumanTicket::getStatus, status));
    }

    /**
     * 通过 WebSocket 推送工单状态更新
     */
    private void pushUpdate(Long ticketId, String status) {
        try {
            messagingTemplate.convertAndSend("/topic/tickets", // 推送工单状态更新到 WebSocket 频道
                Map.of("ticketId", ticketId, "status", status));
        } catch (Exception e) {
            log.warn("WebSocket push failed: {}", e.getMessage()); // 推送失败记录警告
        }
    }

    /**
     * 获取当前登录用户 ID
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 获取认证信息
        Object cred = auth.getCredentials(); // 获取凭证
        return cred instanceof Long l ? l : 0L; // 凭证为 Long 则返回，否则返回 0
    }

    private record ReplyRequest(String content) {} // 回复请求记录
}
