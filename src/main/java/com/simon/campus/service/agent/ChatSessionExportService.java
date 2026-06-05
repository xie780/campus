package com.simon.campus.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.campus.model.entity.ChatMessage;
import com.simon.campus.model.entity.ChatSession;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 会话导出服务：将聊天会话和消息导出为 Markdown 格式
 */
@Service
public class ChatSessionExportService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"); // 时间格式化器
    private final ObjectMapper objectMapper = new ObjectMapper(); // JSON 解析器

    /**
     * 将会话和消息列表转换为 Markdown 文本
     */
    public String toMarkdown(ChatSession session, List<ChatMessage> messages) {
        StringBuilder out = new StringBuilder(); // 构建 Markdown 文本
        out.append("# ").append(emptyToDefault(session.getTitle(), "聊天记录")).append("\n\n"); // 写入标题
        if (session.getCreatedAt() != null) { // 写入创建时间
            out.append("- 创建时间：").append(session.getCreatedAt().format(TIME_FMT)).append("\n");
        }
        out.append("- 会话 ID：").append(session.getSessionId() == null ? "" : session.getSessionId()).append("\n\n"); // 写入会话 ID

        for (ChatMessage message : messages) { // 遍历每条消息
            out.append("## ").append(roleLabel(message.getRole())).append("\n\n"); // 写入角色标题
            if (message.getCreatedAt() != null) { // 写入消息时间
                out.append("> ").append(message.getCreatedAt().format(TIME_FMT)).append("\n\n");
            }
            ImageMeta image = readImageMeta(message.getToolCalls()); // 读取图片元信息
            if (image.imageUrl() != null) { // 若有图片则写入图片链接
                out.append("![").append(emptyToDefault(image.imageName(), "上传图片")).append("](")
                    .append(image.imageUrl()).append(")\n\n");
            }
            out.append(emptyToDefault(message.getContent(), "")).append("\n\n"); // 写入消息内容
        }
        return out.toString(); // 返回 Markdown 文本
    }

    /**
     * 从消息的 toolCalls 字段中读取图片元信息
     */
    private ImageMeta readImageMeta(String toolCalls) {
        if (toolCalls == null || toolCalls.isBlank()) return new ImageMeta(null, null); // 空值返回空元信息
        try {
            var root = objectMapper.readTree(toolCalls); // 解析 JSON
            return new ImageMeta(root.path("imageUrl").asText(null), root.path("imageName").asText(null)); // 提取图片信息
        } catch (Exception e) {
            return new ImageMeta(null, null); // 解析失败返回空元信息
        }
    }

    /**
     * 将角色标识转换为中文标签
     */
    private String roleLabel(String role) {
        if ("user".equals(role)) return "用户"; // 用户
        if ("assistant".equals(role)) return "助手"; // 助手
        if ("teacher".equals(role)) return "老师"; // 老师
        return emptyToDefault(role, "消息"); // 其他角色
    }

    /**
     * 空值替换为默认值
     */
    private String emptyToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value; // 空值返回默认值
    }

    private record ImageMeta(String imageUrl, String imageName) {} // 图片元信息记录
}
