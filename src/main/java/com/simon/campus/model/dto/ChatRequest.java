package com.simon.campus.model.dto;

import lombok.Data;

/**
 * 聊天请求 DTO：封装用户发送的聊天消息
 */
@Data
public class ChatRequest {
    private String sessionId; // 会话 ID（为空则创建新会话）
    private String query; // 用户查询内容
}
