package com.simon.campus.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 配置：启用 STOMP 协议的 WebSocket 消息代理，用于实时聊天流式推送
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 配置消息代理：启用简单代理 /topic，应用目的地前缀 /app
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic"); // 启用简单消息代理，前缀 /topic
        registry.setApplicationDestinationPrefixes("/app"); // 客户端发送消息的前缀
    }

    /**
     * 注册 STOMP 端点：前端通过 /ws 连接 WebSocket
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws") // STOMP 端点路径
            .setAllowedOriginPatterns("*") // 允许所有来源
            .withSockJS(); // 启用 SockJS 降级
    }
}
