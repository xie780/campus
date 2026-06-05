package com.simon.campus.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改密码请求 DTO：封装原密码和新密码
 */
@Data
public class ChangePasswordRequest {

    @NotBlank(message = "原密码不能为空")
    private String oldPassword; // 原密码

    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 50, message = "新密码长度应为6-50位")
    private String newPassword; // 新密码
}
