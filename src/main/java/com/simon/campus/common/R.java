package com.simon.campus.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应封装类：封装 API 返回的状态码、消息和数据
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class R<T> {

    private int code; // 状态码
    private String message; // 消息
    private T data; // 数据

    /**
     * 成功响应（带数据）
     */
    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data); // 返回 200 成功响应
    }

    /**
     * 成功响应（无数据）
     */
    public static <T> R<T> ok() {
        return new R<>(200, "success", null); // 返回 200 成功响应
    }

    /**
     * 失败响应（自定义状态码）
     */
    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null); // 返回自定义错误码响应
    }

    /**
     * 失败响应（默认 500 状态码）
     */
    public static <T> R<T> fail(String message) {
        return new R<>(500, message, null); // 返回 500 错误响应
    }
}
