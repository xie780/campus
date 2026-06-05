package com.simon.campus;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 智慧校园 AI 助手启动类：Spring Boot 应用入口，
 * 排除默认 UserDetailsService 自动配置，启用异步和 Mapper 扫描
 */
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class}) // 排除默认用户详情服务
@MapperScan("com.simon.campus.mapper") // 扫描 Mapper 接口
@EnableAsync // 启用异步方法调用
public class CampusApplication {
    public static void main(String[] args) {
        SpringApplication.run(CampusApplication.class, args); // 启动 Spring Boot 应用
    }
}
