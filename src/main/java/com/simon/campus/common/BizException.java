package com.simon.campus.common;

import lombok.Getter;

/**
 * 业务异常类：携带错误码和错误消息的运行时异常
 */
@Getter
public class BizException extends RuntimeException {

    private final int code; // 错误码

    /**
     * 带错误码和消息的构造方法
     */
    public BizException(int code, String message) {
        super(message); // 设置异常消息
        this.code = code; // 设置错误码
    }

    /**
     * 默认 500 错误码的构造方法
     */
    public BizException(String message) {
        super(message); // 设置异常消息
        this.code = 500; // 默认 500 错误码
    }
}
