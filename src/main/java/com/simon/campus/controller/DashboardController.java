package com.simon.campus.controller;

import com.simon.campus.common.R;
import com.simon.campus.service.admin.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 仪表盘控制器：提供系统统计数据接口
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService; // 仪表盘服务

    /**
     * 获取仪表盘统计数据
     */
    @GetMapping
    public R<Map<String, Object>> dashboard(@RequestParam(defaultValue = "7") int days) {
        return R.ok(dashboardService.getDashboard(days)); // 返回指定天数内的统计数据
    }
}
