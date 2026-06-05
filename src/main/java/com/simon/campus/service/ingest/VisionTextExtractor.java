package com.simon.campus.service.ingest;

/**
 * 视觉文本提取器接口：从 PDF 图片或单张图片中提取可检索的 Markdown 文本
 */
public interface VisionTextExtractor {

    /**
     * 检查视觉解析能力是否可用
     */
    boolean isAvailable();

    /**
     * 从 PDF 的图片页面中提取 Markdown 文本
     */
    String extractPdfImagesAsMarkdown(byte[] pdfBytes) throws Exception;

    /**
     * 从单张图片中提取 Markdown 文本（默认返回空字符串）
     */
    default String extractImageAsMarkdown(byte[] imageBytes, String mimeType) throws Exception {
        return ""; // 默认空实现
    }

    /**
     * 创建空操作实现（所有方法返回空值或 false）
     */
    static VisionTextExtractor noop() {
        return new VisionTextExtractor() {
            @Override
            public boolean isAvailable() {
                return false; // 不可用
            }

            @Override
            public String extractPdfImagesAsMarkdown(byte[] pdfBytes) {
                return ""; // 返回空
            }

            @Override
            public String extractImageAsMarkdown(byte[] imageBytes, String mimeType) {
                return ""; // 返回空
            }
        };
    }
}
