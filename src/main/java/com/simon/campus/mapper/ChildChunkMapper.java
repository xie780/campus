package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.ChildChunk;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 子块 Mapper：提供子块表的 CRUD 和批量插入
 */
@Mapper
public interface ChildChunkMapper extends BaseMapper<ChildChunk> {

    /**
     * 批量插入子块（逐条插入）
     */
    default void insertBatch(List<ChildChunk> list) {
        list.forEach(this::insert); // 逐条插入子块记录
    }
}
