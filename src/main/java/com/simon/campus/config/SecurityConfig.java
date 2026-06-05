package com.simon.campus.config;

import com.simon.campus.common.JwtFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security 配置：配置 JWT 认证过滤器、CORS 和请求授权规则
 */
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter; // JWT 认证过滤器

    /**
     * 配置安全过滤链：禁用 CSRF、启用 CORS、无状态会话、JWT 过滤器、授权规则
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable) // 禁用 CSRF（无状态 API）
            .cors(cors -> cors.configurationSource(corsConfigurationSource())) // 启用 CORS
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 无状态会话
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/auth/**", // 认证接口放行
                    "/actuator/health", // 健康检查放行
                    "/actuator/info", // 应用信息放行
                    "/api/v1/chat/images/**", // 图片访问放行
                    "/api/v1/chat/stream",  // token comes as query param; JwtFilter handles it
                    "/ws/**"                // WebSocket STOMP endpoint (SockJS fallback)
                ).permitAll() // 以上路径无需认证
                .anyRequest().authenticated() // 其余请求需认证
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class) // 在用户名密码过滤器前添加 JWT 过滤器
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new JsonAuthEntryPoint())) // 未认证返回 JSON
            .build(); // 构建过滤链
    }

    /**
     * 配置 CORS：允许本地开发前端跨域访问
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration(); // 创建 CORS 配置
        config.setAllowedOriginPatterns(List.of("http://localhost:5173", "http://localhost:*", "http://127.0.0.1:*")); // 允许的来源
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")); // 允许的 HTTP 方法
        config.setAllowedHeaders(List.of("*")); // 允许所有请求头
        config.setAllowCredentials(true); // 允许携带凭证
        config.setMaxAge(3600L); // 预检请求缓存时间
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(); // 创建 CORS 源
        source.registerCorsConfiguration("/**", config); // 对所有路径生效
        return source; // 返回 CORS 配置源
    }

    /**
     * JSON 格式的认证入口点：未认证时返回 401 JSON 响应
     */
    static class JsonAuthEntryPoint implements AuthenticationEntryPoint {
        @Override
        public void commence(HttpServletRequest req, HttpServletResponse res,
                             AuthenticationException ex) throws IOException {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 设置 401 状态码
            res.setContentType("application/json;charset=UTF-8"); // 设置 JSON 内容类型
            res.getWriter().write("{\"code\":401,\"message\":\"未授权，请先登录\",\"data\":null}"); // 写入 JSON 错误响应
        }
    }
}
