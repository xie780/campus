package com.simon.campus.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 搜索测试结果视图对象 VO：封装检索测试的命中结果，用于调试和验证
 */
@Data
@Builder
public class SearchTestResultVO {
    private String query; // 查询文本
    private int totalHits; // 命中总数
    private List<HitItem> hits; // 命中项列表

    /**
     * 命中项视图对象：展示单个检索命中的子块信息
     */
    @Data
    @Builder
    public static class HitItem {
        private String childId; // 子块 ID
        private String parentId; // 父块 ID
        private String docTitle; // 文档标题
        private String headingPath; // 章节路径
        private String content; // 子块内容
        private double score; // 匹配分数
        private Integer pageStart; // 起始页码
        private String source; // 召回来源："dense" | "bm25"
    }
}
