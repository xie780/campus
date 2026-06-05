package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.ParentChunk;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 父块 Mapper：提供父块表的 CRUD 和批量插入
 */
@Mapper
public interface ParentChunkMapper extends BaseMapper<ParentChunk> {

    /**
     * 批量插入父块（逐条插入）
     */
    default void insertBatch(List<ParentChunk> list) {
        list.forEach(this::insert); // 逐条插入父块记录
    }
}
