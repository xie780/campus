package com.simon.campus.service.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.mapper.DepartmentContactMapper;
import com.simon.campus.model.entity.DepartmentContact;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 部门联系方式查询工具：从数据库查询院系或行政部门的联系方式（联系人、电话、邮箱、办公地点、办公时间）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DepartmentContactTool {

    private final DepartmentContactMapper mapper; // 部门联系方式数据库 Mapper

    /**
     * 查询部门联系方式：按部门名称或代码模糊查询，不填则返回全部
     */
    public ToolResult query(String department) {
        try {
            List<DepartmentContact> contacts; // 查询结果列表
            if (department == null || department.isBlank()) { // 未指定部门名称
                contacts = mapper.selectList(null); // 查询全部部门
            } else {
                LambdaQueryWrapper<DepartmentContact> qw = new LambdaQueryWrapper<DepartmentContact>()
                    .like(DepartmentContact::getDepartmentName, department) // 按部门名称模糊匹配
                    .or().like(DepartmentContact::getDepartmentCode, department); // 或按部门代码模糊匹配
                contacts = mapper.selectList(qw); // 执行查询
            }

            List<Map<String, Object>> rows = contacts.stream().map(c -> { // 转换为结构化行
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("department", c.getDepartmentName()); // 部门名称
                if (c.getContactPerson() != null) m.put("contact", c.getContactPerson()); // 联系人
                if (c.getPhone() != null)          m.put("phone", c.getPhone()); // 电话
                if (c.getEmail() != null)           m.put("email", c.getEmail()); // 邮箱
                if (c.getOfficeLocation() != null)  m.put("office", c.getOfficeLocation()); // 办公地点
                if (c.getOfficeHours() != null)     m.put("hours", c.getOfficeHours()); // 办公时间
                return m;
            }).collect(Collectors.toList()); // 收集为列表

            String summary = contacts.isEmpty() // 生成摘要
                ? "未找到部门\"" + department + "\"的联系方式" // 无结果
                : "找到 " + contacts.size() + " 个部门联系方式"; // 有结果

            return ToolResult.builder() // 构建工具结果
                .success(true)
                .toolName("query_department_contact")
                .params(Map.of("department", department != null ? department : "all"))
                .data(rows)
                .summary(summary)
                .dataSource("学校通讯录")
                .updatedAt("2026-03-01")
                .build();
        } catch (Exception e) {
            log.error("DepartmentContactTool error: {}", e.getMessage()); // 记录错误
            return ToolResult.builder().success(false).toolName("query_department_contact")
                .error("查询部门联系方式失败：" + e.getMessage()).build(); // 返回失败结果
        }
    }
}
