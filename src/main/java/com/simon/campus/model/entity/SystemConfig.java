package com.simon.campus.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统配置实体：对应 system_config 表，存储系统级键值对配置
 */
@Data
@TableName("system_config")
public class SystemConfig {
    @TableId(type = IdType.AUTO)
    private Long id; // 配置 ID（自增主键）
    private String configKey; // 配置键
    private String configValue; // 配置值
    private String configType; // 配置类型（string / int / double / bool / json）
    private String description; // 配置描述
    private Long updatedBy; // 最后更新者 ID
    private LocalDateTime updatedAt; // 最后更新时间
}
