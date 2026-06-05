package com.simon.campus.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 查询扩展 DTO：存储查询改写后的主查询、子查询和关键词
 */
@Data
public class QueryExpansion {
    private String mainQuery; // 主查询
    private List<String> subQueries; // 子查询列表
    private List<String> keywords; // 关键词列表
}
