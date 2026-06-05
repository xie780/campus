package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工工单实体：对应 human_tickets 表，存储转人工的客服工单
 */
@Data
@TableName("human_tickets")
public class HumanTicket {
    @TableId(type = IdType.AUTO)
    private Long id; // 工单 ID（自增主键）
    private String ticketNo; // 工单编号
    private String sessionId; // 关联会话 ID
    private Long userId; // 提交用户 ID
    private Long assignedTo; // 分配的客服 ID
    private String subject; // 工单主题
    private String issueType; // 问题类型
    private String urgency; // 紧急程度
    private String status; // 工单状态
    private String aiSummary; // AI 生成的摘要
    private Integer rating; // 用户评分
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
}
