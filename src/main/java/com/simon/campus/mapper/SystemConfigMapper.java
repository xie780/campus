package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置 Mapper：提供系统配置表的 CRUD 操作
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {}
