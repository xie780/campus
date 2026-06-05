package com.simon.campus.service.ingest;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档解析器：使用 Apache Tika 提取文档文本，支持视觉解析增强，按标题拆分为章节
 */
@Service
@Slf4j
public class DocumentParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile( // 标题正则：匹配中文章节号、数字编号、Markdown 标题等
        "^(第[一二三四五六七八九十百千]+[章节条款]|\\d+\\.\\d*\\s|[一二三四五六七八九十]+[、.]|#+\\s).{2,50}$",
        Pattern.MULTILINE
    );

    private static final Pattern PAGE_BREAK = Pattern.compile("\\f|(?:---\\s*第\\s*\\d+\\s*页\\s*---)"); // 分页符模式

    /**
     * 解析后的章节记录：包含标题、内容、起始页和层级
     */
    public record ParsedSection(String heading, String content, int pageStart, int level) {}

    private final VisionTextExtractor visionTextExtractor; // 视觉文本提取器

    public DocumentParser() { // 默认构造（无视觉解析）
        this(VisionTextExtractor.noop());
    }

    @Autowired
    public DocumentParser(ObjectProvider<VisionTextExtractor> visionTextExtractorProvider) { // Spring 注入构造
        this(visionTextExtractorProvider.getIfAvailable(VisionTextExtractor::noop));
    }

    DocumentParser(VisionTextExtractor visionTextExtractor) { // 直接构造
        this.visionTextExtractor = visionTextExtractor == null ? VisionTextExtractor.noop() : visionTextExtractor;
    }

    /**
     * 解析文档（不含视觉增强）
     */
    public List<ParsedSection> parse(InputStream inputStream, String contentType) throws Exception {
        return parse(inputStream, contentType, false); // 不含视觉解析
    }

    /**
     * 解析文档用于入库（含视觉增强）
     */
    public List<ParsedSection> parseForIngest(InputStream inputStream, String contentType) throws Exception {
        return parse(inputStream, contentType, true); // 含视觉解析
    }

    /**
     * 核心解析逻辑：提取文本 → 可选视觉增强 → 拆分章节
     */
    private List<ParsedSection> parse(InputStream inputStream, String contentType, boolean includeVisualPdfText) throws Exception {
        byte[] bytes = inputStream.readAllBytes(); // 读取全部字节
        String fullText = extractText(bytes, contentType); // Tika 提取文本
        if (includeVisualPdfText && isPdf(contentType)) { // PDF 且需要视觉解析
            String visualText = extractVisualPdfText(bytes, fullText); // 视觉解析
            if (!visualText.isBlank()) { // 有视觉文本
                fullText = mergeText(fullText, visualText); // 合并文本
            }
        }
        return splitIntoSections(fullText); // 拆分为章节
    }

    /**
     * 使用 Apache Tika 提取文档文本
     */
    private String extractText(byte[] bytes, String contentType) throws Exception {
        BodyContentHandler handler = new BodyContentHandler(-1); // 无长度限制
        Metadata metadata = new Metadata(); // 元数据
        if (contentType != null) {
            metadata.set(Metadata.CONTENT_TYPE, contentType); // 设置内容类型
        }
        ParseContext context = new ParseContext(); // 解析上下文
        AutoDetectParser parser = new AutoDetectParser(); // 自动检测解析器
        parser.parse(new ByteArrayInputStream(bytes), handler, metadata, context); // 执行解析
        return handler.toString(); // 返回提取的文本
    }

    /**
     * 使用视觉模型提取 PDF 图片中的文本
     */
    private String extractVisualPdfText(byte[] pdfBytes, String tikaText) throws Exception {
        if (!visionTextExtractor.isAvailable()) { // 视觉解析不可用
            if (tikaText == null || tikaText.isBlank()) { // Tika 也未提取到文本
                log.warn("PDF 文本解析为空，且视觉解析未启用或未配置 DashScope API Key"); // 记录警告
            }
            return ""; // 返回空
        }
        return visionTextExtractor.extractPdfImagesAsMarkdown(pdfBytes); // 调用视觉提取
    }

    /**
     * 合并 Tika 文本和视觉解析文本
     */
    private String mergeText(String text, String visualText) {
        String cleanText = text == null ? "" : text.strip(); // 清理 Tika 文本
        String cleanVisual = visualText == null ? "" : visualText.strip(); // 清理视觉文本
        if (cleanText.isBlank()) return cleanVisual; // 仅视觉文本
        if (cleanVisual.isBlank()) return cleanText; // 仅 Tika 文本
        return cleanText + "\n\n# PDF 图片解析内容\n\n" + cleanVisual; // 合并
    }

    /**
     * 判断是否为 PDF 类型
     */
    private boolean isPdf(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("pdf"); // 包含 pdf
    }

    /**
     * 将文本按标题拆分为章节
     */
    private List<ParsedSection> splitIntoSections(String text) {
        List<ParsedSection> sections = new ArrayList<>(); // 章节列表
        String[] lines = text.split("\n"); // 按行拆分

        StringBuilder currentContent = new StringBuilder(); // 当前章节内容
        String currentHeading = "引言"; // 当前标题（默认"引言"）
        int currentPage = 1; // 当前页码
        int currentLevel = 0; // 当前层级

        for (String line : lines) { // 遍历每行
            long ffCount = line.chars().filter(c -> c == '\f').count(); // 统计分页符
            currentPage += (int) ffCount; // 更新页码
            String cleanLine = line.replace("\f", "").trim(); // 去除分页符

            if (cleanLine.isEmpty()) { // 空行
                currentContent.append("\n"); // 保留换行
                continue;
            }

            if (isHeading(cleanLine)) { // 检测到标题
                String content = currentContent.toString().strip(); // 保存当前章节
                if (!content.isEmpty()) {
                    sections.add(new ParsedSection(currentHeading, content, currentPage, currentLevel)); // 添加章节
                }
                currentHeading = cleanLine; // 更新标题
                currentLevel = detectLevel(cleanLine); // 检测层级
                currentContent = new StringBuilder(); // 重置内容
            } else {
                currentContent.append(cleanLine).append("\n"); // 追加内容
            }
        }

        String content = currentContent.toString().strip(); // 处理最后一个章节
        if (!content.isEmpty()) {
            sections.add(new ParsedSection(currentHeading, content, currentPage, currentLevel)); // 添加末尾章节
        }

        if (sections.isEmpty() && !text.isBlank()) { // 无章节时整段作为一个章节
            sections.add(new ParsedSection("全文", text.strip(), 1, 0));
        }

        return sections; // 返回章节列表
    }

    /**
     * 判断一行是否为标题
     */
    private boolean isHeading(String line) {
        if (line.length() > 80) return false; // 过长不是标题
        return HEADING_PATTERN.matcher(line).find(); // 匹配标题模式
    }

    /**
     * 检测标题层级
     */
    private int detectLevel(String heading) {
        if (heading.startsWith("#")) { // Markdown 标题
            int level = 0;
            for (char c : heading.toCharArray()) { // 计算 # 数量
                if (c == '#') level++;
                else break;
            }
            return level; // 返回层级
        }
        if (heading.matches("^第[一二三四五六七八九十百千]+章.*")) return 1; // 章级
        if (heading.matches("^第[一二三四五六七八九十百千]+节.*")) return 2; // 节级
        if (heading.matches("^\\d+\\.\\s.*")) return 2; // 数字编号
        if (heading.matches("^\\d+\\.\\d+.*")) return 3; // 二级数字编号
        if (heading.matches("^[一二三四五六七八九十]+[、.].*")) return 2; // 中文编号
        return 1; // 默认一级
    }
}
