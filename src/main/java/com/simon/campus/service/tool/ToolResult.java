package com.simon.campus.service.tool;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具调用结果：封装工具执行后的返回数据，包括成功状态、工具名称、参数、数据、摘要、来源和错误信息
 */
@Data
@Builder
public class ToolResult {
    private boolean success; // 是否执行成功
    private String toolName; // 工具名称
    private Map<String, Object> params; // 调用参数
    private Object data; // 返回数据
    private String summary; // 结果摘要
    private String dataSource; // 数据来源
    private String updatedAt; // 数据更新时间
    private String error; // 错误信息
}
