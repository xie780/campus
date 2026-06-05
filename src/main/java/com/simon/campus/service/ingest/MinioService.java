package com.simon.campus.service.ingest;

import io.minio.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MinIO 对象存储服务：提供文件上传、下载和删除操作，支持本地文件同步删除
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MinioService {

    private final MinioClient minioClient; // MinIO 客户端

    @Value("${minio.bucket-name}")
    private String bucketName; // 存储桶名称

    @Value("${minio.local-data-dir:}")
    private String localDataDir; // 本地数据目录（可选，用于同步删除）

    /**
     * 上传文件到 MinIO
     */
    public String upload(MultipartFile file, String docId) throws Exception {
        String ext = ""; // 文件扩展名
        String originalName = file.getOriginalFilename(); // 原始文件名
        if (originalName != null && originalName.contains(".")) { // 提取扩展名
            ext = originalName.substring(originalName.lastIndexOf("."));
        }
        String objectKey = "docs/" + docId + ext; // 对象键 = docs/文档ID.扩展名

        minioClient.putObject(PutObjectArgs.builder() // 上传到 MinIO
            .bucket(bucketName)
            .object(objectKey)
            .stream(file.getInputStream(), file.getSize(), -1)
            .contentType(file.getContentType())
            .build());

        return objectKey; // 返回对象键
    }

    /**
     * 从 MinIO 下载文件
     */
    public InputStream download(String objectKey) throws Exception {
        return minioClient.getObject(GetObjectArgs.builder() // 获取对象流
            .bucket(bucketName)
            .object(objectKey)
            .build());
    }

    /**
     * 删除 MinIO 对象（忽略异常）
     */
    public void delete(String objectKey) {
        try {
            deleteStrict(objectKey); // 委托给严格删除
        } catch (Exception e) {
            log.warn("Failed to delete MinIO object {}: {}", objectKey, e.getMessage()); // 记录警告
        }
    }

    /**
     * 严格删除 MinIO 对象（抛出异常），并同步删除本地文件
     */
    public void deleteStrict(String objectKey) throws Exception {
        if (objectKey == null || objectKey.isBlank()) return; // 空键跳过
        minioClient.removeObject(RemoveObjectArgs.builder() // 从 MinIO 删除
            .bucket(bucketName)
            .object(objectKey)
            .build());
        log.info("[DELETE_FLOW] step=minio_delete bucket={} object={}", bucketName, objectKey); // 记录删除日志
        deleteLocalObjectIfConfigured(objectKey); // 同步删除本地文件
    }

    /**
     * 如果配置了本地数据目录，则删除对应的本地文件
     */
    private void deleteLocalObjectIfConfigured(String objectKey) throws Exception {
        if (localDataDir == null || localDataDir.isBlank()) return; // 未配置本地目录则跳过

        Path base = Path.of(localDataDir).toAbsolutePath().normalize(); // 本地数据根目录
        Path target = base.resolve(bucketName).resolve(objectKey).normalize(); // 目标文件路径
        if (!target.startsWith(base)) { // 路径遍历安全检查
            throw new IllegalArgumentException("非法本地文件路径: " + objectKey);
        }

        boolean deleted = Files.deleteIfExists(target); // 删除本地文件
        log.info("[DELETE_FLOW] step=local_file_delete path={} deleted={}", target, deleted); // 记录删除结果
    }
}
