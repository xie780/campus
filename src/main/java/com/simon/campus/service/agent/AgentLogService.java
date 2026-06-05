package com.simon.campus.service.agent;

import com.simon.campus.mapper.AgentLogMapper;
import com.simon.campus.model.entity.AgentLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Agent 日志服务：异步保存 Agent 编排日志到数据库
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentLogService {

    private final AgentLogMapper agentLogMapper; // Agent 日志 Mapper

    /**
     * 异步保存 Agent 日志
     */
    @Async
    public void save(AgentLog log) {
        try {
            log.setCreatedAt(LocalDateTime.now()); // 设置创建时间
            agentLogMapper.insert(log); // 插入日志记录
        } catch (Exception e) {
            AgentLogService.log.warn("Failed to save agent log: {}", e.getMessage()); // 保存失败记录警告
        }
    }
}
