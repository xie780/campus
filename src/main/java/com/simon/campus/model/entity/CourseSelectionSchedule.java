package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 选课时间安排实体：对应 course_selection_schedule 表，存储各学期选课阶段的时间安排
 */
@Data
@TableName("course_selection_schedule")
public class CourseSelectionSchedule {
    @TableId(type = IdType.AUTO)
    private Long id; // 记录 ID（自增主键）
    private String term; // 学期
    private String phaseName; // 阶段名称
    private String phaseType; // 阶段类型
    private String targetGrades; // 目标年级
    private LocalDateTime startTime; // 开始时间
    private LocalDateTime endTime; // 结束时间
    private String notes; // 备注
    private LocalDateTime createdAt; // 创建时间
}
