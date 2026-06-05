package com.simon.campus.service.agent;

import com.simon.campus.common.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * 聊天图片存储服务：将用户上传的图片保存到本地文件系统并提供加载功能
 */
@Service
public class ChatImageStorageService {

    @Value("${chat.image-upload-dir:uploads/chat-images}")
    private String uploadDir; // 图片上传目录

    /**
     * 保存图片到文件系统，返回存储信息
     */
    public StoredImage save(byte[] bytes, String mimeType, String originalName) throws Exception {
        String ext = extensionFor(mimeType, originalName); // 根据 MIME 类型和原始文件名推断扩展名
        String fileName = UUID.randomUUID().toString().replace("-", "") + ext; // 生成唯一文件名
        Path dir = Path.of(uploadDir).toAbsolutePath().normalize(); // 获取上传目录的绝对路径
        Files.createDirectories(dir); // 确保目录存在
        Path target = dir.resolve(fileName).normalize(); // 构建目标文件路径
        if (!target.startsWith(dir)) { // 路径遍历安全检查
            throw new BizException("非法图片路径");
        }
        Files.write(target, bytes); // 写入图片文件
        return new StoredImage(fileName, "/api/v1/chat/images/" + fileName, originalName); // 返回存储信息
    }

    /**
     * 根据文件名加载图片
     */
    public ImageResource load(String fileName) throws Exception {
        String safeName = Path.of(fileName).getFileName().toString(); // 提取安全文件名（防止路径遍历）
        Path dir = Path.of(uploadDir).toAbsolutePath().normalize(); // 获取上传目录
        Path target = dir.resolve(safeName).normalize(); // 构建目标路径
        if (!target.startsWith(dir) || !Files.exists(target)) { // 安全检查和存在性检查
            throw new BizException(404, "图片不存在");
        }
        return new ImageResource(Files.readAllBytes(target), contentTypeFor(safeName)); // 返回图片资源和 MIME 类型
    }

    /**
     * 测试用：设置上传目录
     */
    void setUploadDirForTest(String uploadDir) {
        this.uploadDir = uploadDir; // 设置上传目录
    }

    /**
     * 根据 MIME 类型和原始文件名推断文件扩展名
     */
    private String extensionFor(String mimeType, String originalName) {
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT); // 文件名转小写
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) return ".jpg"; // JPEG
        if (lowerName.endsWith(".png")) return ".png"; // PNG
        if (lowerName.endsWith(".gif")) return ".gif"; // GIF
        if (lowerName.endsWith(".webp")) return ".webp"; // WebP
        if ("image/jpeg".equalsIgnoreCase(mimeType)) return ".jpg"; // JPEG MIME
        if ("image/gif".equalsIgnoreCase(mimeType)) return ".gif"; // GIF MIME
        if ("image/webp".equalsIgnoreCase(mimeType)) return ".webp"; // WebP MIME
        return ".png"; // 默认 PNG
    }

    /**
     * 根据文件扩展名推断 Content-Type
     */
    private String contentTypeFor(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT); // 文件名转小写
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg"; // JPEG
        if (lower.endsWith(".gif")) return "image/gif"; // GIF
        if (lower.endsWith(".webp")) return "image/webp"; // WebP
        return "image/png"; // 默认 PNG
    }

    public record StoredImage(String fileName, String url, String originalName) {} // 存储图片信息记录
    public record ImageResource(byte[] bytes, String contentType) {} // 图片资源记录
}
