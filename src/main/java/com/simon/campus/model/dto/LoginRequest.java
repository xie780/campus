package com.simon.campus.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求 DTO：封装用户登录所需的用户名和密码
 */
@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username; // 用户名或邮箱

    @NotBlank(message = "密码不能为空")
    private String password; // 密码
}
