package com.simon.campus.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.mapper.ParentChunkMapper;
import com.simon.campus.model.dto.RecallCandidate;
import com.simon.campus.model.entity.ParentChunk;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 父子上下文组装器（RAG Stage 5）：将重排序后的子块按父块分组、评分，
 * 加载 top 3–6 父块文本作为 RAG 生成阶段的参考上下文
 * 评分公式：0.5 × max_child_score + 0.3 × log(1 + hit_count) + 0.2 × coverage
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParentChildContextAssembler {

    private static final int MIN_PARENTS = 3; // 最少返回父块数
    private static final int MAX_PARENTS = 6; // 最多返回父块数
    private static final int TOKEN_BUDGET = 4000; // 上下文 token 预算

    private final ParentChunkMapper parentChunkMapper; // 父块数据库 Mapper

    /**
     * 组装结果：上下文文本 + 来源引用列表
     */
    @Data
    @AllArgsConstructor
    public static class AssembledContext {
        private final String contextText; // 拼接后的上下文文本
        private final List<SourceRef> sourceRefs; // 来源引用列表
    }

    /**
     * 来源引用：记录上下文中每个参考资料的文档标题、章节路径、页码和父块 ID
     */
    @Data
    @AllArgsConstructor
    public static class SourceRef {
        private final String docTitle; // 文档标题
        private final String headingPath; // 章节路径
        private final Integer pageStart; // 起始页码
        private final String parentId; // 父块 ID
    }

    /**
     * 组装上下文：将重排序后的子块按父块分组评分，加载 top 父块文本
     */
    public AssembledContext assemble(List<RecallCandidate> rerankedChildren) {
        if (rerankedChildren.isEmpty()) { // 无候选子块
            return new AssembledContext("", Collections.emptyList()); // 返回空上下文
        }

        // 按父块 ID 分组子块
        Map<String, List<RecallCandidate>> byParent = rerankedChildren.stream()
            .collect(Collectors.groupingBy(RecallCandidate::getParentId)); // 按 parentId 分组

        // 对每个父块评分
        List<ParentScore> scored = new ArrayList<>(); // 父块评分列表
        for (Map.Entry<String, List<RecallCandidate>> e : byParent.entrySet()) {
            String parentId = e.getKey(); // 父块 ID
            List<RecallCandidate> children = e.getValue(); // 该父块下的子块列表
            double maxChildScore = children.stream().mapToDouble(RecallCandidate::getScore).max().orElse(0); // 子块最高分
            int hitCount = children.size(); // 命中子块数
            int totalChildren = byParent.values().stream().mapToInt(List::size).sum(); // 所有子块总数
            double coverage = totalChildren > 0 ? (double) hitCount / rerankedChildren.size() : 0; // 覆盖率
            double parentScore = 0.5 * maxChildScore + 0.3 * Math.log(1 + hitCount) + 0.2 * coverage; // 父块综合评分
            scored.add(new ParentScore(parentId, parentScore, children.get(0))); // 加入评分列表
        }
        scored.sort(Comparator.comparingDouble(ParentScore::score).reversed()); // 按分数降序排列

        // 在 token 预算内选择 top 3–6 父块
        int targetCount = Math.min(MAX_PARENTS, Math.max(MIN_PARENTS, scored.size())); // 目标父块数
        List<ParentScore> selected = scored.subList(0, Math.min(targetCount, scored.size())); // 取前 N 个

        // 从数据库加载父块内容
        List<String> parentIds = selected.stream().map(ParentScore::parentId).toList(); // 提取父块 ID 列表
        Map<String, ParentChunk> parentMap = new HashMap<>(); // 父块 ID → 实体映射
        if (!parentIds.isEmpty()) {
            LambdaQueryWrapper<ParentChunk> qw = new LambdaQueryWrapper<ParentChunk>()
                .in(ParentChunk::getParentId, parentIds); // 按 ID 列表批量查询
            parentChunkMapper.selectList(qw)
                .forEach(p -> parentMap.put(p.getParentId(), p)); // 填充映射
        }

        StringBuilder sb = new StringBuilder(); // 上下文文本构建器
        List<SourceRef> sourceRefs = new ArrayList<>(); // 来源引用列表
        int totalChars = 0; // 已使用字符数
        int sectionIdx = 1; // 参考资料编号

        for (ParentScore ps : selected) { // 遍历选中的父块
            ParentChunk parent = parentMap.get(ps.parentId()); // 获取父块实体
            if (parent == null) continue; // 跳过数据库中不存在的父块

            String content = parent.getContent(); // 父块文本内容
            // 粗略 token 估算：1 token ≈ 1.5 个中文字符
            int estimatedTokens = (int) (content.length() / 1.5); // 估算 token 数
            if (totalChars > 0 && totalChars + estimatedTokens > TOKEN_BUDGET) break; // 超出预算则停止

            String heading = parent.getHeadingPath() != null ? parent.getHeadingPath() : parent.getDocTitle(); // 章节标题
            String pageRef = parent.getPageStart() != null ? "第" + parent.getPageStart() + "页" : ""; // 页码引用

            sb.append("【参考资料").append(sectionIdx++).append("】") // 参考资料编号
              .append(parent.getDocTitle()); // 文档标题
            if (!pageRef.isEmpty()) sb.append(" ").append(pageRef); // 追加页码
            if (heading != null && !heading.isBlank()) sb.append(" > ").append(heading); // 追加章节路径
            sb.append("\n").append(content).append("\n\n"); // 追加内容

            sourceRefs.add(new SourceRef( // 添加来源引用
                parent.getDocTitle(),
                parent.getHeadingPath(),
                parent.getPageStart(),
                parent.getParentId()
            ));
            totalChars += estimatedTokens; // 累计 token 数
        }

        log.debug("Context assembled: {} parents, ~{} tokens", sourceRefs.size(), totalChars); // 记录组装统计
        return new AssembledContext(sb.toString().strip(), sourceRefs); // 返回组装结果
    }

    /**
     * 父块评分记录：父块 ID、综合分数、样本子块
     */
    private record ParentScore(String parentId, double score, RecallCandidate sampleChild) {}
}
