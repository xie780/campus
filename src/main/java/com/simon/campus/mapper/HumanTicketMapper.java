package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.HumanTicket;
import org.apache.ibatis.annotations.Mapper;

/**
 * 人工工单 Mapper：提供人工工单表的 CRUD 操作
 */
@Mapper
public interface HumanTicketMapper extends BaseMapper<HumanTicket> {}
