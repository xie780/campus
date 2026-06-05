package com.simon.campus.service.ingest;

import java.util.List;

/**
 * 可见性策略：定义文档访问级别常量及可见性判断逻辑
 * 0=全部可见, 1=教师可见, 2=旧版管理员(等同教师), 3=学生可见
 */
public final class VisibilityPolicy {

    public static final int ALL = 0; // 全部可见
    public static final int TEACHER = 1; // 仅教师可见
    public static final int LEGACY_ADMIN_AS_TEACHER = 2; // 旧版管理员（等同教师）
    public static final int STUDENT = 3; // 仅学生可见

    private VisibilityPolicy() {} // 私有构造，禁止实例化

    /**
     * 根据查看者级别返回其可见的文档级别列表
     */
    public static List<Integer> visibleLevelsForViewer(int viewerLevel) {
        if (viewerLevel == TEACHER || viewerLevel == LEGACY_ADMIN_AS_TEACHER) { // 教师或旧管理员
            return List.of(ALL, TEACHER, LEGACY_ADMIN_AS_TEACHER); // 可见：全部 + 教师 + 旧管理员
        }
        return List.of(ALL, STUDENT); // 学生可见：全部 + 学生
    }

    /**
     * 判断指定文档级别对查看者是否可见
     */
    public static boolean canView(Integer docLevel, int viewerLevel) {
        return visibleLevelsForViewer(viewerLevel).contains(docLevel == null ? ALL : docLevel); // 空级别视为全部可见
    }

    /**
     * 判断是否为合法的文档访问级别
     */
    public static boolean isValidDocumentLevel(int level) {
        return level == ALL || level == TEACHER || level == STUDENT; // 仅 0/1/3 合法
    }

    /**
     * 获取访问级别的中文标签
     */
    public static String label(Integer level) {
        if (level == null) return "全部可见"; // 空级别
        return switch (level) {
            case ALL -> "全部可见"; // 0
            case TEACHER, LEGACY_ADMIN_AS_TEACHER -> "仅教师可见"; // 1/2
            case STUDENT -> "仅学生可见"; // 3
            default -> "未知"; // 其他
        };
    }
}
