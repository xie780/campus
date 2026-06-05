package com.simon.campus.controller;

import com.simon.campus.common.R;
import com.simon.campus.model.dto.ChangePasswordRequest;
import com.simon.campus.model.dto.LoginRequest;
import com.simon.campus.model.dto.RegisterRequest;
import com.simon.campus.model.dto.ResetPasswordRequest;
import com.simon.campus.model.vo.LoginResponse;
import com.simon.campus.service.user.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器：处理用户注册、登录、修改密码和重置密码
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService; // 用户服务

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public R<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok(userService.register(request)); // 调用注册服务并返回登录响应
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(userService.login(request)); // 调用登录服务并返回登录响应
    }

    /**
     * 修改密码
     */
    @PostMapping("/change-password")
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication auth) {
        userService.changePassword((Long) auth.getCredentials(), request.getOldPassword(), request.getNewPassword()); // 根据用户 ID 和新旧密码修改
        return R.ok(null);
    }

    /**
     * 重置密码
     */
    @PostMapping("/reset-password")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(request.getUsername(), request.getEmail()); // 根据用户名和邮箱重置密码
        return R.ok(null);
    }
}
