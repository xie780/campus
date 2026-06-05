package com.simon.campus.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 召回候选项 DTO：表示多路召回中的一个候选子块，包含内容和来源信息
 */
@Data
@Builder
public class RecallCandidate {
    private String childId; // 子块 ID
    private String parentId; // 父块 ID
    private String docId; // 文档 ID
    private String docTitle; // 文档标题
    private String headingPath; // 章节路径
    private String content; // 子块文本内容
    private Integer pageStart; // 起始页码
    private double score; // 召回分数
    private String source; // 召回来源："dense" | "bm25" | "faq"
}
