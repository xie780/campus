package com.simon.campus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置：配置 RedisTemplate 的序列化策略，Key 用 String，Value 用 JSON
 */
@Configuration
public class RedisConfig {

    /**
     * 创建 RedisTemplate Bean，设置 Key 和 Value 的序列化器
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>(); // 创建 RedisTemplate
        template.setConnectionFactory(factory); // 设置连接工厂

        StringRedisSerializer stringSerializer = new StringRedisSerializer(); // String 序列化器
        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(Object.class); // JSON 序列化器

        template.setKeySerializer(stringSerializer); // Key 使用 String 序列化
        template.setHashKeySerializer(stringSerializer); // Hash Key 使用 String 序列化
        template.setValueSerializer(jsonSerializer); // Value 使用 JSON 序列化
        template.setHashValueSerializer(jsonSerializer); // Hash Value 使用 JSON 序列化
        template.afterPropertiesSet(); // 初始化配置
        return template; // 返回配置好的 RedisTemplate
    }
}
