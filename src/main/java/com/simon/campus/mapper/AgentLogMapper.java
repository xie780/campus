package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.AgentLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 日志 Mapper：提供 Agent 日志表的 CRUD 操作
 */
@Mapper
public interface AgentLogMapper extends BaseMapper<AgentLog> {
}
