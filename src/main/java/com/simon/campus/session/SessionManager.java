package com.simon.campus.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话管理器：基于 Redis 管理用户会话上下文的创建、获取、保存和删除，
 * 活跃会话 TTL 2 小时，人工待处理会话 TTL 24 小时
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionManager {

    private static final String KEY_PREFIX = "session:"; // Redis 键前缀
    private static final Duration ACTIVE_TTL = Duration.ofHours(2); // 活跃会话 TTL
    private static final Duration HUMAN_TTL  = Duration.ofHours(24); // 人工待处理会话 TTL

    private final StringRedisTemplate redisTemplate; // Redis 模板
    private final ObjectMapper objectMapper = new ObjectMapper() // JSON 序列化器
        .registerModule(new JavaTimeModule()); // 注册 Java 时间模块

    /**
     * 获取或创建会话上下文：若 sessionId 对应的会话存在则返回，否则创建新会话
     */
    public SessionContext getOrCreate(String sessionId, Long userId, String username, String role) {
        if (sessionId != null) { // sessionId 非空
            Optional<SessionContext> existing = get(sessionId); // 尝试获取已有会话
            if (existing.isPresent()) { // 会话存在
                return existing.get(); // 返回已有会话
            }
        }
        String newId = sessionId != null ? sessionId : UUID.randomUUID().toString().replace("-", ""); // 生成会话 ID
        SessionContext ctx = SessionContext.builder() // 构建新会话
            .sessionId(newId) // 设置会话 ID
            .userId(userId) // 设置用户 ID
            .username(username) // 设置用户名
            .role(role) // 设置角色
            .createdAt(LocalDateTime.now()) // 设置创建时间
            .lastActiveAt(LocalDateTime.now()) // 设置最后活跃时间
            .build(); // 构建会话
        save(ctx); // 保存到 Redis
        return ctx; // 返回新会话
    }

    /**
     * 从 Redis 获取会话上下文
     */
    public Optional<SessionContext> get(String sessionId) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId); // 从 Redis 读取 JSON
        if (json == null) return Optional.empty(); // 不存在返回空
        try {
            return Optional.of(objectMapper.readValue(json, SessionContext.class)); // 反序列化并返回
        } catch (Exception e) {
            log.warn("Failed to deserialize session {}: {}", sessionId, e.getMessage()); // 记录警告
            return Optional.empty(); // 反序列化失败返回空
        }
    }

    /**
     * 保存会话上下文到 Redis，根据状态设置不同的 TTL
     */
    public void save(SessionContext ctx) {
        try {
            String key = KEY_PREFIX + ctx.getSessionId(); // 构建 Redis 键
            Duration ttl = "HUMAN_PENDING".equals(ctx.getStatus()) ? HUMAN_TTL : ACTIVE_TTL; // 根据状态选择 TTL
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(ctx), ttl); // 保存到 Redis
        } catch (Exception e) {
            log.error("Failed to save session {}: {}", ctx.getSessionId(), e.getMessage()); // 记录错误
        }
    }

    /**
     * 删除会话上下文
     */
    public void delete(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId); // 从 Redis 删除
    }
}
