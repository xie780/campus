package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天会话 Mapper：提供聊天会话表的 CRUD 操作
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSession> {
}
