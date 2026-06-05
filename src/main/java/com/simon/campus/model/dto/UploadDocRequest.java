package com.simon.campus.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 上传文档请求 DTO：封装文档上传所需的标题、分类和可见范围
 */
@Data
public class UploadDocRequest {
    @NotBlank
    private String title; // 文档标题
    private String categoryCode; // 知识分类代码
    @NotNull
    private Integer accessLevel; // 可见范围：0=全部可见, 1=教师及以上, 2=管理员
}
