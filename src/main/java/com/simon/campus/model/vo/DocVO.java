package com.simon.campus.model.vo;

import com.simon.campus.service.ingest.VisibilityPolicy;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档视图对象 VO：封装文档列表展示所需的元数据和状态信息
 */
@Data
public class DocVO {
    private String docId; // 文档 ID
    private String title; // 文档标题
    private String fileName; // 原始文件名
    private String fileType; // 文件 MIME 类型
    private Long fileSize; // 文件大小（字节）
    private String categoryCode; // 知识分类代码
    private String status; // 入库状态
    private Integer accessLevel; // 可见范围
    private String accessLevelName; // 可见范围中文名
    private Integer parentChunkCount; // 父块数量
    private Integer childChunkCount; // 子块数量
    private String errorMsg; // 错误信息
    private String createdBy; // 创建者
    private LocalDateTime createdAt; // 创建时间
    private LocalDateTime updatedAt; // 更新时间

    /**
     * 根据可见级别获取中文名称
     */
    public static String accessLevelName(Integer level) {
        return VisibilityPolicy.label(level); // 委托给可见性策略获取标签
    }
}
