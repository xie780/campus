package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 日志实体：对应 agent_logs 表，记录 RAG 流水线各阶段的耗时和统计信息
 */
@Data
@TableName("agent_logs")
public class AgentLog {
    @TableId(type = IdType.AUTO)
    private Long id; // 日志 ID（自增主键）
    private String sessionId; // 会话 ID
    private Long userId; // 用户 ID
    private String intent; // 意图标签
    private String userQuery; // 用户原始查询
    private String rewrittenQuery; // 改写后的查询
    private Integer recallCount; // 召回候选数
    private Integer rerankCount; // 重排后候选数
    private Integer parentCount; // 上下文父块数
    private Integer stage1Ms; // Stage 1 耗时（ms）
    private Integer stage2Ms; // Stage 2 耗时（ms）
    private Integer stage3Ms; // Stage 3 耗时（ms）
    private Integer stage4Ms; // Stage 4 耗时（ms）
    private Integer stage5Ms; // Stage 5 耗时（ms）
    private Integer stage6Ms; // Stage 6 耗时（ms）
    private Integer totalMs; // 总耗时（ms）
    private Integer promptTokens; // 提示 token 数
    private Integer completionTokens; // 生成 token 数
    private String hitDocs; // 命中文档 JSON
    private LocalDateTime createdAt; // 创建时间
}
