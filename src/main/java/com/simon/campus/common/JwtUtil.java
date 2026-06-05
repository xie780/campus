package com.simon.campus.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：提供令牌生成、解析和用户信息提取功能
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret; // JWT 签名密钥

    @Value("${jwt.expiration}")
    private long expiration; // 令牌过期时间（毫秒）

    /**
     * 获取签名密钥：不足 32 字节时自动补零（HS256 要求）
     */
    private SecretKey getSecretKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8); // 密钥转字节
        // JJWT requires >= 32 bytes for HS256
        if (keyBytes.length < 32) { // 密钥长度不足
            byte[] padded = new byte[32]; // 创建 32 字节数组
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length); // 复制原始密钥
            return Keys.hmacShaKeyFor(padded); // 返回补零后的密钥
        }
        return Keys.hmacShaKeyFor(keyBytes); // 返回原始密钥
    }

    /**
     * 生成 JWT 令牌：包含用户 ID、用户名和角色
     */
    public String generateToken(Long userId, String username, String role) {
        return Jwts.builder()
            .subject(String.valueOf(userId)) // 设置主体为用户 ID
            .claim("username", username) // 自定义声明：用户名
            .claim("role", role) // 自定义声明：角色
            .issuedAt(new Date()) // 签发时间
            .expiration(new Date(System.currentTimeMillis() + expiration)) // 过期时间
            .signWith(getSecretKey()) // 使用密钥签名
            .compact(); // 生成令牌字符串
    }

    /**
     * 解析 JWT 令牌，返回 Claims
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
            .verifyWith(getSecretKey()) // 设置验证密钥
            .build() // 构建解析器
            .parseSignedClaims(token) // 解析令牌
            .getPayload(); // 返回 Claims
    }

    /**
     * 从令牌中提取用户 ID
     */
    public Long getUserId(String token) {
        return Long.parseLong(parseToken(token).getSubject()); // 解析主体为用户 ID
    }

    /**
     * 从令牌中提取用户名
     */
    public String getUsername(String token) {
        return parseToken(token).get("username", String.class); // 获取 username 声明
    }

    /**
     * 从令牌中提取角色
     */
    public String getRole(String token) {
        return parseToken(token).get("role", String.class); // 获取 role 声明
    }
}
