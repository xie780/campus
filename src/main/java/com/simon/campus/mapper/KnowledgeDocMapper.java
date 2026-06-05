package com.simon.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.campus.model.entity.KnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 知识文档 Mapper：提供知识文档表的 CRUD 和处理结果更新
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDoc> {

    /**
     * 更新文档处理结果：设置入库状态、块数量和错误信息
     */
    @Update("UPDATE knowledge_docs SET status=#{status}, parent_chunk_count=#{parentCount}, " +
            "child_chunk_count=#{childCount}, error_msg=#{errorMsg} WHERE doc_id=#{docId}")
    void updateProcessResult(@Param("docId") String docId,
                             @Param("status") String status,
                             @Param("parentCount") int parentCount,
                             @Param("childCount") int childCount,
                             @Param("errorMsg") String errorMsg);
}
