package com.simon.campus.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话视图对象 VO：封装前端展示的会话列表信息
 */
@Data
@Builder
public class SessionVO {
    private String sessionId; // 会话 ID
    private String title; // 会话标题
    private String status; // 会话状态
    private Integer messageCount; // 消息数量
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
}
