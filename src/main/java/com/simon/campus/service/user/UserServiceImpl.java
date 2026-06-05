package com.simon.campus.service.user;

import cn.hutool.crypto.SecureUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.common.BizException;
import com.simon.campus.common.JwtUtil;
import com.simon.campus.mapper.UserMapper;
import com.simon.campus.model.dto.LoginRequest;
import com.simon.campus.model.dto.RegisterRequest;
import com.simon.campus.model.entity.User;
import com.simon.campus.model.enums.UserRole;
import com.simon.campus.model.vo.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 用户服务实现类：提供用户注册、登录、修改密码和重置密码等功能，
 * 使用 SHA-256 加密密码，JWT 生成令牌
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_RESET_PASSWORD = "123456"; // 重置密码的默认密码

    private final UserMapper userMapper; // 用户数据库 Mapper
    private final JwtUtil jwtUtil; // JWT 工具类

    /**
     * 用户注册：校验用户名和邮箱唯一性，创建用户并返回登录响应
     */
    @Override
    public LoginResponse register(RegisterRequest request) {
        // 校验用户名和邮箱唯一性
        long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
            .eq(User::getUsername, request.getUsername()) // 匹配用户名
            .or().eq(User::getEmail, request.getEmail()) // 或匹配邮箱
        );
        if (count > 0) { // 用户名或邮箱已存在
            throw new BizException(400, "用户名或邮箱已被注册"); // 抛出业务异常
        }

        String role = StringUtils.hasText(request.getRole()) // 判断角色是否有效
            && UserRole.isValid(request.getRole())
            ? request.getRole().toUpperCase() : "STUDENT"; // 默认角色为学生

        User user = new User(); // 创建用户实体
        user.setUsername(request.getUsername()); // 设置用户名
        user.setEmail(request.getEmail()); // 设置邮箱
        user.setPassword(SecureUtil.sha256(request.getPassword())); // SHA-256 加密密码
        user.setRole(role); // 设置角色
        user.setNickname(StringUtils.hasText(request.getNickname())
            ? request.getNickname() : request.getUsername()); // 昵称默认为用户名
        user.setStatus(1); // 设置状态为启用
        userMapper.insert(user); // 插入数据库

        return buildResponse(user, jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole())); // 返回登录响应
    }

    /**
     * 用户登录：验证用户名/邮箱和密码，返回登录响应
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.findByUsernameOrEmail(request.getUsername()); // 按用户名或邮箱查找
        if (user == null || !SecureUtil.sha256(request.getPassword()).equals(user.getPassword())) { // 验证密码
            throw new BizException(401, "用户名或密码错误"); // 抛出认证异常
        }
        if (user.getStatus() == 0) { // 账号被禁用
            throw new BizException(403, "账号已被禁用，请联系管理员"); // 抛出权限异常
        }

        return buildResponse(user, jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole())); // 返回登录响应
    }

    /**
     * 修改密码：验证原密码，更新为新密码
     */
    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.selectById(userId); // 按 ID 查找用户
        if (user == null) throw new BizException(404, "用户不存在"); // 用户不存在
        if (!SecureUtil.sha256(oldPassword).equals(user.getPassword())) { // 原密码验证
            throw new BizException(400, "原密码错误"); // 原密码错误
        }
        if (SecureUtil.sha256(newPassword).equals(user.getPassword())) { // 新旧密码相同
            throw new BizException(400, "新密码不能与原密码相同"); // 新密码不能与原密码相同
        }
        user.setPassword(SecureUtil.sha256(newPassword)); // 设置新密码（加密）
        userMapper.updateById(user); // 更新数据库
    }

    /**
     * 重置密码：通过用户名和邮箱验证身份，将密码重置为默认密码
     */
    @Override
    public void resetPassword(String username, String email) {
        User user = userMapper.findByUsernameAndEmail(username, email); // 按用户名和邮箱查找
        if (user == null) { // 未找到匹配用户
            throw new BizException(404, "用户名或邮箱不匹配"); // 抛出异常
        }
        user.setPassword(SecureUtil.sha256(DEFAULT_RESET_PASSWORD)); // 重置为默认密码（加密）
        userMapper.updateById(user); // 更新数据库
    }

    /**
     * 构建登录响应：封装令牌和用户信息
     */
    private LoginResponse buildResponse(User user, String token) {
        LoginResponse resp = new LoginResponse(); // 创建响应对象
        resp.setToken(token); // 设置 JWT 令牌
        resp.setUserId(user.getId()); // 设置用户 ID
        resp.setUsername(user.getUsername()); // 设置用户名
        resp.setRole(user.getRole()); // 设置角色
        resp.setNickname(user.getNickname()); // 设置昵称
        resp.setAvatarUrl(user.getAvatarUrl()); // 设置头像 URL
        return resp; // 返回响应
    }
}
