package com.simon.campus.model.vo;

import lombok.Data;

/**
 * 登录响应 VO：封装登录成功后返回的令牌和用户信息
 */
@Data
public class LoginResponse {

    private String token; // JWT 令牌

    private Long userId; // 用户 ID

    private String username; // 用户名

    private String role; // 角色

    private String nickname; // 昵称

    private String avatarUrl; // 头像 URL
}
