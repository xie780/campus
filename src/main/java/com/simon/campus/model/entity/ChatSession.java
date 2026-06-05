package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话实体：对应 chat_sessions 表，存储用户与 AI 的会话信息
 */
@Data
@TableName("chat_sessions")
public class ChatSession {
    @TableId
    private String sessionId; // 会话 ID（主键）
    private Long userId; // 所属用户 ID
    private String title; // 会话标题
    private String status; // 会话状态
    private Integer messageCount; // 消息数量
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
}
