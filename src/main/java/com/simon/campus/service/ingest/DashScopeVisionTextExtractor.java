package com.simon.campus.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DashScope 视觉文本提取器：使用多模态大模型（qwen3-vl-flash）解析 PDF 图片和上传图片为 Markdown
 */
@Service
@Slf4j
public class DashScopeVisionTextExtractor implements VisionTextExtractor {

    private static final String IMAGE_PROMPT = """ 
        qwenvl markdown。
        请解析这页 PDF 图片中的全部可读内容，输出适合知识库检索的 Markdown。
        要求：
        1. 保留标题、段落、表格、列表、流程、图示中的文字和关键信息。
        2. 对非文字图片用简短中文描述说明其含义。
        3. 不要输出寒暄、不要编造图片中不存在的信息。
        """;

    private static final String CHAT_IMAGE_PROMPT = """ 
        请解析用户上传的图片内容，输出后续问答可直接引用的中文 Markdown。
        要求：
        1. 如果图片包含文字、表格、截图、票据、成绩单、通知公告，请尽量完整转写。
        2. 如果图片是场景或物品，请描述关键元素、位置关系和可能含义。
        3. 不要编造图片中不存在的信息；看不清的内容说明"无法辨认"。
        """;

    @Value("${dashscope.api-key}")
    private String apiKey; // DashScope API Key

    @Value("${dashscope.base-url}")
    private String baseUrl; // DashScope 基础 URL

    @Value("${knowledge.vision.pdf-image-enabled:true}")
    private boolean enabled; // 是否启用 PDF 图片解析

    @Value("${knowledge.vision.pdf-render-dpi:144}")
    private int renderDpi; // PDF 渲染 DPI

    @Value("${knowledge.vision.pdf-max-pages:30}")
    private int maxPages; // 最大解析页数

    @Value("${models.vision-parser.model:qwen3-vl-flash}")
    private String model; // 视觉模型名称

    @Value("${models.vision-parser.temperature:0.1}")
    private double temperature; // 生成温度

    @Value("${models.vision-parser.max-tokens:2048}")
    private int maxTokens; // 最大生成 Token

    private final HttpClient httpClient = HttpClient.newBuilder() // HTTP 客户端
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 序列化器

    /**
     * 检查视觉解析是否可用
     */
    @Override
    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank() && !apiKey.contains("your-dashscope-api-key"); // 启用且 API Key 有效
    }

    /**
     * 将 PDF 逐页渲染为图片并调用视觉模型提取 Markdown
     */
    @Override
    public String extractPdfImagesAsMarkdown(byte[] pdfBytes) throws Exception {
        if (!isAvailable()) return ""; // 不可用返回空

        List<String> pageTexts = new ArrayList<>(); // 每页解析结果
        try (PDDocument document = PDDocument.load(pdfBytes)) { // 加载 PDF
            PDFRenderer renderer = new PDFRenderer(document); // PDF 渲染器
            int pages = Math.min(document.getNumberOfPages(), Math.max(1, maxPages)); // 限制页数
            log.info("[INGEST_FLOW] step=pdf_visual_parse pages={} maxPages={} model={} dpi={}",
                document.getNumberOfPages(), pages, model, renderDpi); // 记录解析参数
            for (int pageIndex = 0; pageIndex < pages; pageIndex++) { // 逐页解析
                BufferedImage image = renderer.renderImageWithDPI(pageIndex, renderDpi, ImageType.RGB); // 渲染为图片
                String markdown = parsePageImage(image, pageIndex + 1); // 调用视觉模型
                if (!markdown.isBlank()) { // 有内容
                    pageTexts.add("## 第 " + (pageIndex + 1) + " 页图片解析\n\n" + markdown.strip()); // 添加页码标记
                }
            }
            if (document.getNumberOfPages() > pages) { // 超过最大页数
                pageTexts.add("> 视觉解析已达到最大页数限制，仅处理前 " + pages + " 页。"); // 添加提示
            }
        }
        return String.join("\n\n", pageTexts); // 合并所有页面
    }

    /**
     * 解析单张上传图片为 Markdown
     */
    @Override
    public String extractImageAsMarkdown(byte[] imageBytes, String mimeType) throws Exception {
        if (!isAvailable()) return ""; // 不可用返回空
        String safeMimeType = (mimeType == null || mimeType.isBlank()) ? "image/png" : mimeType; // 默认 MIME 类型
        long start = System.currentTimeMillis(); // 记录开始时间
        String base64 = Base64.getEncoder().encodeToString(imageBytes); // Base64 编码
        log.info("[MODEL_CALL] type=vision_chat_image model={} mimeType={} bytes={} prompt={}",
            model, safeMimeType, imageBytes.length, abbreviate(CHAT_IMAGE_PROMPT)); // 记录调用日志
        String content = callVisionModel("data:" + safeMimeType + ";base64," + base64, CHAT_IMAGE_PROMPT, 120); // 调用视觉模型
        log.info("[MODEL_RETURN] type=vision_chat_image model={} costMs={} response={}",
            model, System.currentTimeMillis() - start, abbreviate(content)); // 记录返回日志
        return content; // 返回解析结果
    }

    /**
     * 解析单页 PDF 图片
     */
    private String parsePageImage(BufferedImage image, int pageNumber) throws Exception {
        long start = System.currentTimeMillis(); // 记录开始时间
        String base64 = toJpegBase64(image); // 转为 JPEG Base64
        log.info("[MODEL_CALL] type=vision_pdf_page model={} page={} image={}x{} prompt={}",
            model, pageNumber, image.getWidth(), image.getHeight(), abbreviate(IMAGE_PROMPT)); // 记录调用日志
        String content = callVisionModel("data:image/jpeg;base64," + base64, IMAGE_PROMPT, 180); // 调用视觉模型
        log.info("[MODEL_RETURN] type=vision_pdf_page model={} page={} costMs={} response={}",
            model, pageNumber, System.currentTimeMillis() - start, abbreviate(content)); // 记录返回日志
        return content; // 返回解析结果
    }

    /**
     * 调用 DashScope 视觉模型 API
     */
    private String callVisionModel(String imageUrl, String prompt, int timeoutSeconds) throws Exception {
        Map<String, Object> imagePart = Map.of( // 图片消息部分
            "type", "image_url",
            "image_url", Map.of("url", imageUrl)
        );
        Map<String, Object> textPart = Map.of( // 文本消息部分
            "type", "text",
            "text", prompt
        );

        Map<String, Object> message = new LinkedHashMap<>(); // 消息对象
        message.put("role", "user"); // 用户角色
        message.put("content", List.of(imagePart, textPart)); // 多模态内容

        Map<String, Object> body = new LinkedHashMap<>(); // 请求体
        body.put("model", model); // 模型名称
        body.put("messages", List.of(message)); // 消息列表
        body.put("temperature", temperature); // 温度
        body.put("max_tokens", maxTokens); // 最大 Token
        body.put("enable_thinking", false); // 禁用思考模式

        HttpRequest request = HttpRequest.newBuilder() // 构建 HTTP 请求
            .uri(URI.create(baseUrl + "/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString()); // 发送请求
        if (response.statusCode() != 200) { // 请求失败
            throw new RuntimeException("DashScope 视觉解析失败 [" + response.statusCode() + "]: " + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body()); // 解析响应
        return root.path("choices").get(0).path("message").path("content").asText(""); // 提取生成内容
    }

    /**
     * 将 BufferedImage 转为 JPEG Base64 字符串
     */
    private String toJpegBase64(BufferedImage image) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", out); // 写入 JPEG
            return Base64.getEncoder().encodeToString(out.toByteArray()); // 转 Base64
        }
    }

    /**
     * 截断过长的日志字符串
     */
    private String abbreviate(String value) {
        if (value == null) return ""; // 空值返回空串
        String normalized = value.replaceAll("\\s+", " ").strip(); // 压缩空白
        int max = 3000; // 最大长度
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "...[truncated]"; // 超长截断
    }
}
