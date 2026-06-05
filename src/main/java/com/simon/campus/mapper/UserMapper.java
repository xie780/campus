package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper：提供用户表的 CRUD 和按用户名/邮箱查询
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 按用户名或邮箱查找用户
     */
    @Select("SELECT * FROM users WHERE username = #{usernameOrEmail} OR email = #{usernameOrEmail} LIMIT 1")
    User findByUsernameOrEmail(String usernameOrEmail);

    /**
     * 按用户名和邮箱联合查找用户（用于重置密码验证）
     */
    @Select("SELECT * FROM users WHERE username = #{username} AND email = #{email} LIMIT 1")
    User findByUsernameAndEmail(String username, String email);
}
