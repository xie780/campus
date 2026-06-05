package com.simon.campus.service.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.mapper.CourseSelectionScheduleMapper;
import com.simon.campus.model.entity.CourseSelectionSchedule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 选课查询工具：从数据库查询指定学期的选课安排（各轮选课/退课时间），返回结构化结果
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CourseSelectionTool {

    private final CourseSelectionScheduleMapper mapper; // 选课安排数据库 Mapper
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"); // 日期格式化器

    /**
     * 查询选课安排：按学期查询各轮选课阶段，默认查询当前学期
     */
    public ToolResult query(String term) {
        try {
            String resolvedTerm = term == null || term.isBlank() ? "2025-2026-2" : term; // 确定查询学期
            LambdaQueryWrapper<CourseSelectionSchedule> qw = new LambdaQueryWrapper<CourseSelectionSchedule>()
                .eq(CourseSelectionSchedule::getTerm, resolvedTerm) // 按学期筛选
                .orderByAsc(CourseSelectionSchedule::getStartTime); // 按开始时间升序
            List<CourseSelectionSchedule> phases = mapper.selectList(qw); // 查询选课阶段列表

            List<Map<String, Object>> rows = phases.stream().map(p -> { // 转换为结构化行
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("phase", p.getPhaseName()); // 阶段名称
                m.put("type", p.getPhaseType()); // 阶段类型
                m.put("targets", p.getTargetGrades()); // 目标年级
                m.put("start", p.getStartTime().format(FMT)); // 开始时间
                m.put("end", p.getEndTime().format(FMT)); // 结束时间
                if (p.getNotes() != null) m.put("notes", p.getNotes()); // 备注
                return m;
            }).collect(Collectors.toList()); // 收集为列表

            String summary = phases.isEmpty() // 生成摘要
                ? "未找到学期 " + resolvedTerm + " 的选课安排" // 无结果
                : resolvedTerm + " 学期选课共 " + phases.size() + " 个阶段"; // 有结果

            return ToolResult.builder() // 构建工具结果
                .success(true)
                .toolName("query_course_selection")
                .params(Map.of("term", resolvedTerm))
                .data(rows)
                .summary(summary)
                .dataSource("教务处选课系统")
                .updatedAt("2026-01-10")
                .build();
        } catch (Exception e) {
            log.error("CourseSelectionTool error: {}", e.getMessage()); // 记录错误
            return ToolResult.builder().success(false).toolName("query_course_selection")
                .error("查询选课安排失败：" + e.getMessage()).build(); // 返回失败结果
        }
    }
}
