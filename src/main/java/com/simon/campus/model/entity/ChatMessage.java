package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体：对应 chat_messages 表，存储会话中的每条消息
 */
@Data
@TableName("chat_messages")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id; // 消息 ID（自增主键）
    private String sessionId; // 所属会话 ID
    private Long userId; // 发送用户 ID
    private String role; // 角色：user / assistant / system
    private String content; // 消息内容
    private String intent; // 意图标签
    private String sourceRefs; // 引用来源 JSON
    private String toolCalls; // 工具调用 JSON
    private Integer tokenCount; // token 数量
    private LocalDateTime createdAt; // 创建时间
}
