package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 子块实体：对应 child_chunks 表，存储文档拆分后的细粒度子块，用于向量检索和 BM25 索引
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("child_chunks")
public class ChildChunk {

    @TableId
    private String childId; // 子块 ID（主键）

    private String parentId; // 所属父块 ID
    private String docId; // 所属文档 ID
    private String docTitle; // 文档标题
    private String headingPath; // 章节路径
    private String content; // 子块文本内容
    private Integer chunkIndex; // 子块在父块中的序号
    private Integer startOffset; // 在原文中的起始偏移
    private Integer endOffset; // 在原文中的结束偏移
    private Integer tokenCount; // token 数量
    private Integer pageStart; // 起始页码
    private Integer pageEnd; // 结束页码
    private String category; // 知识分类

    /** 继承自所属文档的可见范围：0=全部 1/2=教师 3=学生 */
    private Integer accessLevel; // 可见范围
}
