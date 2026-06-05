package com.simon.campus.service.ingest;

/**
 * 入库错误消息工具：截断异常消息到最大长度，防止数据库字段溢出
 */
final class IngestErrorMessage {

    static final int MAX_ERROR_MSG_LENGTH = 500; // 错误消息最大长度

    private IngestErrorMessage() { // 私有构造，禁止实例化
    }

    /**
     * 从异常中提取错误消息并截断
     */
    static String from(Throwable e) {
        String message = e.getMessage(); // 获取异常消息
        if (message == null || message.isBlank()) { // 消息为空
            message = e.getClass().getSimpleName(); // 使用类名代替
        }
        return message.length() > MAX_ERROR_MSG_LENGTH // 超长截断
            ? message.substring(0, MAX_ERROR_MSG_LENGTH)
            : message;
    }
}
