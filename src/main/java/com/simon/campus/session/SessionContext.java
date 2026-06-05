package com.simon.campus.session;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话上下文：存储用户会话的状态信息，包括身份、意图、消息历史和槽位数据，
 * 用于 RAG 和工具调用流程中传递上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionContext {

    private String sessionId; // 会话 ID
    private Long userId; // 用户 ID
    private String username; // 用户名
    private String role; // 用户角色

    @Builder.Default
    private String status = "ACTIVE"; // 会话状态：ACTIVE / HUMAN_PENDING / CLOSED

    private String currentIntent; // 当前意图

    @Builder.Default
    private List<MessageRecord> history = new ArrayList<>(); // 消息历史（最多保留 20 条）

    @Builder.Default
    private Map<String, Object> slots = new HashMap<>(); // 槽位数据（用于多轮对话状态管理）

    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime lastActiveAt; // 最后活跃时间

    /**
     * 添加消息到历史记录，超过 20 条时移除最早的
     */
    public void addMessage(String role, String content) {
        history.add(new MessageRecord(role, content, LocalDateTime.now())); // 添加消息记录
        if (history.size() > 20) { // 超过上限
            history.remove(0); // 移除最早的消息
        }
        lastActiveAt = LocalDateTime.now(); // 更新活跃时间
    }

    /**
     * 构建最近 N 轮对话的文本，用于 LLM 上下文
     */
    public String buildHistoryText(int maxTurns) {
        int start = Math.max(0, history.size() - maxTurns * 2); // 计算起始索引
        StringBuilder sb = new StringBuilder(); // 构建文本
        for (int i = start; i < history.size(); i++) { // 遍历历史消息
            MessageRecord msg = history.get(i); // 获取消息
            sb.append("user".equals(msg.role) ? "用户: " : "助手: ").append(msg.content).append("\n"); // 拼接消息
        }
        return sb.toString().strip(); // 返回文本
    }

    /**
     * 消息记录：存储单条消息的角色、内容和时间戳
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageRecord {
        private String role; // 角色：user / assistant / system
        private String content; // 消息内容
        private LocalDateTime timestamp; // 时间戳
    }
}
