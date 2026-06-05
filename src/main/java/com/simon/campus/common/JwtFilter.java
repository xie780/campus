package com.simon.campus.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器：从请求头或查询参数中提取 JWT 令牌，
 * 解析后设置 Spring Security 认证上下文
 */
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil; // JWT 工具类

    /**
     * 过滤器核心逻辑：提取令牌 → 解析用户信息 → 设置认证上下文
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String token = extractToken(request); // 提取 JWT 令牌
        if (token != null) { // 令牌存在
            try {
                Long userId   = jwtUtil.getUserId(token); // 解析用户 ID
                String username = jwtUtil.getUsername(token); // 解析用户名
                String role     = jwtUtil.getRole(token); // 解析角色

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) { // 未认证
                    var auth = new UsernamePasswordAuthenticationToken( // 创建认证令牌
                        username, userId, // 主体和凭证
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)) // 授予角色权限
                    );
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request)); // 设置请求详情
                    SecurityContextHolder.getContext().setAuthentication(auth); // 设置认证上下文
                }
            } catch (Exception ignored) {
                // Invalid/expired token — continue unauthenticated // 令牌无效/过期，继续未认证
            }
        }

        chain.doFilter(request, response); // 继续过滤器链
    }

    /**
     * 从 Authorization 请求头或 token 查询参数中提取 JWT 令牌
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION); // 获取 Authorization 头
        if (authHeader != null && authHeader.startsWith("Bearer ")) { // Bearer 令牌格式
            return authHeader.substring(7); // 提取令牌部分
        }
        String param = request.getParameter("token"); // 从查询参数获取（SSE EventSource 场景）
        if (param != null && !param.isBlank()) { // 参数非空
            return param; // 返回查询参数中的令牌
        }
        return null; // 未找到令牌
    }
}
