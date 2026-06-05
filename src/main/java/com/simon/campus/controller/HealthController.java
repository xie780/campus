package com.simon.campus.controller;

import com.simon.campus.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查控制器：提供系统运行状态检测接口
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /**
     * 健康检查接口：返回系统运行状态
     */
    @GetMapping("/hello")
    public R<String> hello() {
        return R.ok("SmartCampus Backend is running 🎓"); // 返回系统运行状态信息
    }
}
