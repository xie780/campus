package com.simon.campus.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天消息视图对象 VO：封装前端展示的聊天消息，包含引用来源
 */
@Data
@Builder
public class ChatMessageVO {
    private Long id; // 消息 ID
    private String role; // 角色：user / assistant
    private String content; // 消息内容
    private String intent; // 意图标签
    private String imageUrl; // 图片 URL
    private String imageName; // 图片名称
    private List<SourceRefVO> sourceRefs; // 引用来源列表
    private LocalDateTime createdAt; // 创建时间

    /**
     * 引用来源视图对象：展示命中文档的来源信息
     */
    @Data
    @Builder
    public static class SourceRefVO {
        private String docTitle; // 文档标题
        private String headingPath; // 章节路径
        private Integer pageStart; // 起始页码
    }
}
