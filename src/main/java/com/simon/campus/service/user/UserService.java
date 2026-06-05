package com.simon.campus.service.user;

import com.simon.campus.model.dto.LoginRequest;
import com.simon.campus.model.dto.RegisterRequest;
import com.simon.campus.model.vo.LoginResponse;

/**
 * 用户服务接口：定义用户注册、登录、修改密码和重置密码等操作
 */
public interface UserService {

    /**
     * 用户注册
     */
    LoginResponse register(RegisterRequest request);

    /**
     * 用户登录
     */
    LoginResponse login(LoginRequest request);

    /**
     * 修改密码
     */
    void changePassword(Long userId, String oldPassword, String newPassword);

    /**
     * 重置密码（通过用户名和邮箱验证）
     */
    void resetPassword(String username, String email);
}
