package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 校历事件实体：对应 academic_calendar 表，存储学期校历安排（开学、放假、考试等）
 */
@Data
@TableName("academic_calendar")
public class AcademicCalendar {
    @TableId(type = IdType.AUTO)
    private Long id; // 事件 ID（自增主键）
    private String term; // 学期
    private String eventName; // 事件名称
    private String eventType; // 事件类型
    private LocalDate startDate; // 开始日期
    private LocalDate endDate; // 结束日期
    private String description; // 事件描述
    private LocalDateTime createdAt; // 创建时间
}
