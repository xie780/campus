package com.simon.campus.service.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.mapper.AcademicCalendarMapper;
import com.simon.campus.model.entity.AcademicCalendar;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 校历查询工具：从数据库查询指定学期的校历安排（开学、放假、考试等事件），返回结构化结果
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AcademicCalendarTool {

    private final AcademicCalendarMapper mapper;

    /**
     * Spring AI 工具方法：查询校历
     */
    @Tool(description = "查询校历安排，包括开学放假考试等事件")
    public ToolResult queryAcademicCalendar(
            @ToolParam(description = "学期，格式如 2025-2026-2，不填则查当前学期") String term) {
        ToolResult result = query(term);
        ToolResultCapture.set(result);
        return result;
    }

    /**
     * 查询校历：按学期查询校历事件列表，默认查询当前学期
     */
    public ToolResult query(String term) {
        try {
            String resolvedTerm = term == null || term.isBlank() ? currentTerm() : term; // 确定查询学期
            LambdaQueryWrapper<AcademicCalendar> qw = new LambdaQueryWrapper<AcademicCalendar>()
                .eq(AcademicCalendar::getTerm, resolvedTerm) // 按学期筛选
                .orderByAsc(AcademicCalendar::getStartDate); // 按开始日期升序
            List<AcademicCalendar> events = mapper.selectList(qw); // 查询校历事件列表

            List<Map<String, Object>> rows = events.stream().map(e -> { // 转换为结构化行
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("event", e.getEventName()); // 事件名称
                m.put("type", e.getEventType()); // 事件类型
                m.put("start", e.getStartDate().toString()); // 开始日期
                if (e.getEndDate() != null) m.put("end", e.getEndDate().toString()); // 结束日期
                if (e.getDescription() != null) m.put("note", e.getDescription()); // 备注
                return m;
            }).collect(Collectors.toList()); // 收集为列表

            String summary = events.isEmpty() // 生成摘要
                ? "未找到学期 " + resolvedTerm + " 的校历信息" // 无结果
                : resolvedTerm + " 学期校历共 " + events.size() + " 项安排"; // 有结果

            return ToolResult.builder() // 构建工具结果
                .success(true)
                .toolName("query_academic_calendar")
                .params(Map.of("term", resolvedTerm))
                .data(rows)
                .summary(summary)
                .dataSource("教务处校历系统")
                .updatedAt("2026-02-01")
                .build();
        } catch (Exception e) {
            log.error("AcademicCalendarTool error: {}", e.getMessage()); // 记录错误
            return ToolResult.builder().success(false).toolName("query_academic_calendar")
                .error("查询校历失败：" + e.getMessage()).build(); // 返回失败结果
        }
    }

    /**
     * 获取当前学期标识
     */
    private String currentTerm() {
        return "2025-2026-2"; // 当前学期
    }
}
