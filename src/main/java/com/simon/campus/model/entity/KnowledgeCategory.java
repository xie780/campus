package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识分类实体：对应 knowledge_categories 表，存储知识库的分类信息
 */
@Data
@TableName("knowledge_categories")
public class KnowledgeCategory {

    @TableId(type = IdType.AUTO)
    private Long id; // 分类 ID（自增主键）

    private String name; // 分类名称
    private String code; // 分类代码
    private Integer status; // 状态：1=启用 0=禁用
    private Integer sortOrder; // 排序序号
}
