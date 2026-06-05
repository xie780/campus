package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.FaqPair;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * FAQ 问答对 Mapper：提供 FAQ 表的 CRUD 和查询已启用的问答对
 */
@Mapper
public interface FaqPairMapper extends BaseMapper<FaqPair> {

    /**
     * 查询所有已启用的 FAQ 问答对，按优先级和命中次数降序排列
     */
    @Select("SELECT * FROM faq_pairs WHERE enabled = 1 ORDER BY priority DESC, hit_count DESC")
    List<FaqPair> findAllEnabled();
}
