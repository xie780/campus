package com.simon.campus.service.ingest;

import org.springframework.http.MediaType;
import org.springframework.web.util.UriUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档预览记录：封装文件流、媒体类型和 Content-Disposition 头信息
 */
public record DocumentPreview(
    InputStream stream, // 文件输入流
    MediaType mediaType, // 媒体类型
    String contentDisposition // Content-Disposition 头值
) {

    /**
     * 创建内联预览（浏览器直接显示）
     */
    public static DocumentPreview of(String objectKey, String fileName, String contentType, InputStream stream) {
        return new DocumentPreview(
            stream,
            resolveMediaType(fileName, contentType), // 解析媒体类型
            "inline; filename*=UTF-8''" + UriUtils.encode(fileName != null ? fileName : objectKey, StandardCharsets.UTF_8) // 内联显示
        );
    }

    /**
     * 创建下载预览（浏览器下载文件）
     */
    public static DocumentPreview download(String objectKey, String fileName, String contentType, InputStream stream) {
        return new DocumentPreview(
            stream,
            resolveMediaType(fileName, contentType), // 解析媒体类型
            "attachment; filename*=UTF-8''" + UriUtils.encode(fileName != null ? fileName : objectKey, StandardCharsets.UTF_8) // 附件下载
        );
    }

    /**
     * 根据文件名和 Content-Type 解析 MediaType
     */
    private static MediaType resolveMediaType(String fileName, String contentType) {
        if (contentType != null && !contentType.isBlank()) { // 优先使用 Content-Type
            try {
                return MediaType.parseMediaType(contentType); // 解析媒体类型
            } catch (Exception ignored) { // 解析失败，回退到扩展名检测
            }
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(); // 转小写
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF; // PDF
        if (lower.endsWith(".txt")) return new MediaType("text", "plain", StandardCharsets.UTF_8); // 文本
        return MediaType.APPLICATION_OCTET_STREAM; // 默认二进制流
    }
}
