package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 部门联系方式实体：对应 department_contacts 表，存储各部门的联系信息
 */
@Data
@TableName("department_contacts")
public class DepartmentContact {
    @TableId(type = IdType.AUTO)
    private Long id; // 记录 ID（自增主键）
    private String departmentName; // 部门名称
    private String departmentCode; // 部门代码
    private String contactPerson; // 联系人
    private String phone; // 联系电话
    private String email; // 邮箱
    private String officeLocation; // 办公地点
    private String officeHours; // 办公时间
    private LocalDateTime createdAt; // 创建时间
}
