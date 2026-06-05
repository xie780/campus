package com.simon.campus.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 配置：创建 MinIO 客户端 Bean，并自动初始化存储桶
 */
@Configuration
@Slf4j
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint; // MinIO 服务端点

    @Value("${minio.access-key}")
    private String accessKey; // 访问密钥

    @Value("${minio.secret-key}")
    private String secretKey; // 密钥

    @Value("${minio.bucket-name}")
    private String bucketName; // 存储桶名称

    /**
     * 创建 MinIO 客户端并确保存储桶存在
     */
    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder() // 构建 MinIO 客户端
            .endpoint(endpoint) // 设置端点
            .credentials(accessKey, secretKey) // 设置凭证
            .build(); // 构建客户端
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build()); // 检查桶是否存在
            if (!exists) { // 桶不存在
                client.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build()); // 创建桶
                log.info("MinIO bucket '{}' created", bucketName); // 记录创建日志
            }
        } catch (Exception e) {
            log.warn("MinIO bucket init failed (is MinIO running?): {}", e.getMessage()); // 记录警告
        }
        return client; // 返回客户端
    }
}
