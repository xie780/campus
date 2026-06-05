package com.simon.campus.model.enums;

/**
 * 用户角色枚举：定义系统中的三种用户角色
 */
public enum UserRole {
    STUDENT, // 学生
    TEACHER, // 教师
    ADMIN; // 管理员

    /**
     * 判断角色名称是否合法
     */
    public static boolean isValid(String role) {
        if (role == null) return false; // 空值不合法
        for (UserRole r : values()) { // 遍历所有角色
            if (r.name().equalsIgnoreCase(role)) return true; // 忽略大小写匹配
        }
        return false; // 无匹配则不合法
    }
}
