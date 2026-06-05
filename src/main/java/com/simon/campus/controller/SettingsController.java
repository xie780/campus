package com.simon.campus.controller;

import com.simon.campus.common.R;
import com.simon.campus.service.admin.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 系统设置控制器：提供系统配置的查看、修改、重置、导入和导出
 */
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SystemConfigService configService; // 系统配置服务

    /**
     * 获取所有系统配置（分组展示）
     */
    @GetMapping("/configs")
    public R<Map<String, Object>> listConfigs() {
        return R.ok(configService.listGrouped()); // 返回分组后的配置
    }

    /**
     * 批量更新系统配置（仅教师和管理员）
     */
    @PutMapping("/configs")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> updateConfigs(@RequestBody Map<String, String> updates) {
        Long userId = currentUserId(); // 获取当前用户 ID
        configService.batchUpdate(updates, userId); // 批量更新配置
        return R.ok(null);
    }

    /**
     * 重置系统配置为默认值（仅教师和管理员）
     */
    @PostMapping("/configs/reset")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> resetConfigs() {
        Long userId = currentUserId(); // 获取当前用户 ID
        configService.resetDefaults(userId); // 重置为默认配置
        return R.ok(null);
    }

    /**
     * 导出系统配置（仅管理员）
     */
    @GetMapping("/configs/export")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, String>> exportConfigs() {
        return R.ok(configService.export()); // 返回所有配置键值对
    }

    /**
     * 导入系统配置（仅管理员）
     */
    @PostMapping("/configs/import")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> importConfigs(@RequestBody Map<String, String> configs) {
        Long userId = currentUserId(); // 获取当前用户 ID
        configService.importConfigs(configs, userId); // 导入配置
        return R.ok(null);
    }

    /**
     * 获取当前登录用户 ID
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 获取认证信息
        if (auth == null) return 0L; // 未认证返回 0
        Object cred = auth.getCredentials(); // 获取凭证
        return cred instanceof Long ? (Long) cred : 0L; // 凭证为 Long 则返回，否则返回 0
    }
}
