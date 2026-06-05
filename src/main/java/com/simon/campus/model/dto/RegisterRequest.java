package com.simon.campus.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求 DTO：封装用户注册所需的用户名、邮箱、密码等信息
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 30, message = "用户名长度 2-30 位")
    private String username; // 用户名

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email; // 邮箱

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度 6-32 位")
    private String password; // 密码

    private String nickname; // 昵称（可选）

    private String role; // 角色：STUDENT / TEACHER / ADMIN，默认 STUDENT
}
