package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档实体：对应 knowledge_docs 表，存储上传文档的元数据和入库状态
 */
@Data
@TableName("knowledge_docs")
public class KnowledgeDoc {

    @TableId(type = IdType.ASSIGN_UUID)
    private String docId; // 文档 ID（UUID 主键）

    private String title; // 文档标题
    private String fileName; // 原始文件名
    private String fileType; // 文件 MIME 类型
    private Long fileSize; // 文件大小（字节）
    private String minioKey; // MinIO 存储键
    private String categoryCode; // 知识分类代码

    /** PROCESSING / READY / FAILED */
    private String status; // 入库状态：PROCESSING=处理中 READY=就绪 FAILED=失败

    /** 0=全部可见  1/2=仅教师可见（兼容旧数据）  3=仅学生可见 */
    private Integer accessLevel; // 可见范围

    private Integer parentChunkCount; // 父块数量
    private Integer childChunkCount; // 子块数量
    private String department; // 所属部门
    private String errorMsg; // 错误信息
    private String createdBy; // 创建者
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间
}
