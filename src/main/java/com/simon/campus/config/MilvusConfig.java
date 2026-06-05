package com.simon.campus.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.*;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Milvus 配置：创建 Milvus 客户端 Bean，并自动初始化向量集合和 HNSW 索引
 */
@Configuration
@Slf4j
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host; // Milvus 主机地址

    @Value("${milvus.port}")
    private int port; // Milvus 端口

    @Value("${milvus.collection-name}")
    private String collectionName; // 集合名称

    private static final int DIMENSION = 1024; // 向量维度

    /**
     * 创建 Milvus 客户端并初始化集合
     */
    @Bean
    public MilvusServiceClient milvusServiceClient() {
        ConnectParam connectParam = ConnectParam.newBuilder() // 构建连接参数
            .withHost(host) // 设置主机
            .withPort(port) // 设置端口
            .build(); // 构建参数
        MilvusServiceClient client = new MilvusServiceClient(connectParam); // 创建客户端
        try {
            initCollection(client); // 初始化集合
        } catch (Exception e) {
            log.warn("Milvus collection init failed (is Milvus running?): {}", e.getMessage()); // 记录警告
        }
        return client; // 返回客户端
    }

    /**
     * 初始化 Milvus 集合：若不存在则创建集合、字段、HNSW 索引并加载到内存
     */
    private void initCollection(MilvusServiceClient client) {
        R<Boolean> hasResp = client.hasCollection( // 检查集合是否存在
            HasCollectionParam.newBuilder().withCollectionName(collectionName).build()
        );
        if (Boolean.TRUE.equals(hasResp.getData())) { // 集合已存在
            log.info("Milvus collection '{}' already exists", collectionName); // 记录日志
            return; // 无需创建
        }

        List<FieldType> fields = Arrays.asList( // 定义集合字段
            FieldType.newBuilder()
                .withName("child_id") // 子块 ID
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPrimaryKey(true) // 主键
                .withAutoID(false) // 不自动生成
                .build(),
            FieldType.newBuilder().withName("parent_id").withDataType(DataType.VarChar).withMaxLength(64).build(), // 父块 ID
            FieldType.newBuilder().withName("doc_id").withDataType(DataType.VarChar).withMaxLength(36).build(), // 文档 ID
            FieldType.newBuilder().withName("doc_title").withDataType(DataType.VarChar).withMaxLength(256).build(), // 文档标题
            FieldType.newBuilder().withName("category").withDataType(DataType.VarChar).withMaxLength(64).build(), // 知识分类
            // 0=全部可见, 1/2=教师可见（兼容旧数据）, 3=学生可见
            FieldType.newBuilder().withName("access_level").withDataType(DataType.Int32).build(), // 可见范围
            FieldType.newBuilder().withName("page_start").withDataType(DataType.Int32).build(), // 起始页码
            FieldType.newBuilder()
                .withName("embedding") // 向量嵌入
                .withDataType(DataType.FloatVector)
                .withDimension(DIMENSION) // 向量维度
                .build()
        );

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder() // 构建创建集合参数
            .withCollectionName(collectionName) // 集合名称
            .withDescription("SmartCampus child chunk vectors") // 集合描述
            .withFieldTypes(fields) // 字段列表
            .build(); // 构建参数
        client.createCollection(createParam); // 创建集合

        // Create HNSW index on embedding field
        CreateIndexParam indexParam = CreateIndexParam.newBuilder() // 构建索引参数
            .withCollectionName(collectionName) // 集合名称
            .withFieldName("embedding") // 索引字段
            .withIndexType(IndexType.HNSW) // HNSW 索引类型
            .withMetricType(MetricType.IP) // 内积距离
            .withExtraParam("{\"M\":16,\"efConstruction\":256}") // HNSW 参数
            .build(); // 构建参数
        client.createIndex(indexParam); // 创建索引

        // Load collection into memory
        client.loadCollection( // 加载集合到内存
            LoadCollectionParam.newBuilder().withCollectionName(collectionName).build()
        );

        log.info("Milvus collection '{}' created and loaded", collectionName); // 记录创建日志
    }
}
