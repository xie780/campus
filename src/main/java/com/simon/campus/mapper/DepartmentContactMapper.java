package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.DepartmentContact;
import org.apache.ibatis.annotations.Mapper;

/**
 * 部门联系方式 Mapper：提供部门联系方式表的 CRUD 操作
 */
@Mapper
public interface DepartmentContactMapper extends BaseMapper<DepartmentContact> {}
