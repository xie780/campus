package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天消息 Mapper：提供聊天消息表的 CRUD 操作
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
