package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体：对应 users 表，存储用户账号、角色和个人信息
 */
@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id; // 用户 ID（自增主键）

    private String username; // 用户名

    private String email; // 邮箱

    private String password; // 密码（SHA-256 加密）

    private String role; // 角色：STUDENT / TEACHER / ADMIN

    private String nickname; // 昵称

    private String avatarUrl; // 头像 URL

    private String college; // 所属学院

    private Integer status; // 状态：1=启用 0=禁用

    private LocalDateTime createdAt; // 创建时间

    private LocalDateTime updatedAt; // 更新时间
}
