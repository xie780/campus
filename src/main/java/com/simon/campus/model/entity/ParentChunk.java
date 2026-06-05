package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 父块实体：对应 parent_chunks 表，存储文档拆分后的粗粒度父块，用于 RAG 上下文组装
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("parent_chunks")
public class ParentChunk {

    @TableId
    private String parentId; // 父块 ID（主键）

    private String docId; // 所属文档 ID
    private String docTitle; // 文档标题
    private String headingPath; // 章节路径
    private String content; // 父块文本内容
    private Integer pageStart; // 起始页码
    private Integer pageEnd; // 结束页码
    private Integer tokenCount; // token 数量
    private String category; // 知识分类

    /** 继承自所属文档的 access_level */
    private Integer accessLevel; // 可见范围：0=全部 1/2=教师 3=学生

    private LocalDateTime createdAt; // 创建时间
}
