package com.simon.campus.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器：捕获业务异常、参数校验异常、文件上传异常和未知异常，返回统一响应格式
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BizException.class)
    public R<?> handleBizException(BizException e) {
        return R.fail(e.getCode(), e.getMessage()); // 返回业务异常响应
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleValidException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream() // 获取字段错误列表
            .map(FieldError::getDefaultMessage) // 提取错误消息
            .collect(Collectors.joining("; ")); // 用分号连接
        return R.fail(400, message); // 返回 400 校验失败响应
    }

    /**
     * 处理文件大小超限异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public R<?> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        return R.fail(400, "文件大小不能超过100MB"); // 返回文件大小超限提示
    }

    /**
     * 处理未知异常
     */
    @ExceptionHandler(Exception.class)
    public R<?> handleException(Exception e) {
        log.error("Unexpected error", e); // 记录错误日志
        return R.fail(500, "服务器内部错误"); // 返回 500 内部错误响应
    }
}
