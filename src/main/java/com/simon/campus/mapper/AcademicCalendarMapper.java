package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.AcademicCalendar;
import org.apache.ibatis.annotations.Mapper;

/**
 * 校历 Mapper：提供校历表的 CRUD 操作
 */
@Mapper
public interface AcademicCalendarMapper extends BaseMapper<AcademicCalendar> {}
