package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.CourseSelectionSchedule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 选课时间安排 Mapper：提供选课时间安排表的 CRUD 操作
 */
@Mapper
public interface CourseSelectionScheduleMapper extends BaseMapper<CourseSelectionSchedule> {}
