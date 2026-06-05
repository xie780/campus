package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FAQ 问答对实体：对应 faq_pairs 表，存储常见问答对，用于 FAQ 路线召回
 */
@Data
@TableName("faq_pairs")
public class FaqPair {
    @TableId(type = IdType.AUTO)
    private Long id; // 问答对 ID（自增主键）
    private String question; // 问题文本
    private String answer; // 答案文本
    private String category; // 知识分类
    private String keywords; // 关键词（逗号分隔）
    private String priority; // 优先级
    private Integer enabled; // 是否启用：1=启用 0=禁用
    private Integer hitCount; // 命中次数
    private String embeddingJson; // 向量嵌入 JSON
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
}
