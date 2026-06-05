package com.simon.campus.service.ingest;

import com.simon.campus.model.entity.ChildChunk;
import com.simon.campus.model.entity.ParentChunk;
import com.simon.campus.service.ingest.DocumentParser.ParsedSection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 父子分块拆分器：将文档解析后的章节拆分为父块（粗粒度）和子块（细粒度），用于 RAG 多路召回
 */
@Service
@Slf4j
public class ParentChildChunkSplitter {

    // Rough char counts (Chinese avg ~1.5 chars/token, English ~4 chars/token)
    private static final int PARENT_MIN_CHARS = 400; // 父块最小字符数
    private static final int PARENT_MAX_CHARS = 800; // 父块最大字符数
    private static final int CHILD_MIN_CHARS  = 80; // 子块最小字符数
    private static final int CHILD_MAX_CHARS  = 160; // 子块最大字符数
    private static final int CHILD_OVERLAP    = 25; // 子块重叠字符数

    /**
     * 拆分结果记录：包含父块列表和子块列表
     */
    public record SplitResult(List<ParentChunk> parents, List<ChildChunk> children) {}

    /**
     * 将文档章节拆分为父子块
     */
    public SplitResult split(String docId, String docTitle, int accessLevel,
                             List<ParsedSection> sections) {
        List<ParentChunk> parents  = new ArrayList<>(); // 父块列表
        List<ChildChunk>  children = new ArrayList<>(); // 子块列表

        for (ParsedSection section : sections) { // 遍历每个章节
            List<String> parentTexts = splitToParents(section.content()); // 拆分为父块文本
            for (String parentText : parentTexts) { // 遍历每个父块
                String parentId = UUID.randomUUID().toString().replace("-", ""); // 生成父块 ID
                String headingPath = section.heading(); // 章节标题路径

                ParentChunk parent = ParentChunk.builder() // 构建父块实体
                    .parentId(parentId)
                    .docId(docId)
                    .docTitle(docTitle)
                    .headingPath(headingPath)
                    .content(parentText)
                    .pageStart(section.pageStart())
                    .pageEnd(section.pageStart())
                    .accessLevel(accessLevel)
                    .build();
                parents.add(parent); // 添加到父块列表

                List<String> childTexts = splitToChildren(parentText); // 将父块拆分为子块文本
                int offset = 0; // 偏移量（用于定位子块在父块中的位置）
                for (int i = 0; i < childTexts.size(); i++) { // 遍历每个子块
                    String childText = childTexts.get(i);
                    String childId = parentId + "_" + i; // 子块 ID = 父块ID_序号
                    int startOffset = findOffset(parentText, childText, offset); // 计算起始偏移
                    int endOffset = startOffset + childText.length(); // 计算结束偏移

                    ChildChunk child = ChildChunk.builder() // 构建子块实体
                        .childId(childId)
                        .parentId(parentId)
                        .docId(docId)
                        .docTitle(docTitle)
                        .headingPath(headingPath)
                        .content(childText)
                        .pageStart(section.pageStart())
                        .startOffset(startOffset)
                        .endOffset(endOffset)
                        .accessLevel(accessLevel)
                        .build();
                    children.add(child); // 添加到子块列表
                    offset = Math.max(0, endOffset - CHILD_OVERLAP); // 更新偏移量（考虑重叠）
                }
            }
        }

        log.info("Split doc {} into {} parents, {} children", docId, parents.size(), children.size()); // 记录拆分结果
        return new SplitResult(parents, children); // 返回拆分结果
    }

    /**
     * 将文本拆分为父块
     */
    private List<String> splitToParents(String text) {
        return splitByLength(text, PARENT_MIN_CHARS, PARENT_MAX_CHARS, 0); // 无重叠
    }

    /**
     * 将文本拆分为子块
     */
    private List<String> splitToChildren(String text) {
        return splitByLength(text, CHILD_MIN_CHARS, CHILD_MAX_CHARS, CHILD_OVERLAP); // 带重叠
    }

    /**
     * 按长度拆分文本：在句子边界处切割，支持重叠
     */
    private List<String> splitByLength(String text, int minChars, int maxChars, int overlap) {
        List<String> result = new ArrayList<>(); // 结果列表
        if (text == null || text.isBlank()) return result; // 空文本返回空列表

        String[] sentences = text.split("(?<=[。！？.!?\\n])"); // 按句子边界拆分
        StringBuilder current = new StringBuilder(); // 当前累积文本

        for (String sentence : sentences) { // 遍历每个句子
            if (sentence.isBlank()) continue; // 跳过空句
            if (current.length() + sentence.length() > maxChars && current.length() >= minChars) { // 超过最大长度
                result.add(current.toString().strip()); // 保存当前块
                String tail = current.toString(); // 保留尾部用于重叠
                current = new StringBuilder(tail.length() > overlap ? tail.substring(tail.length() - overlap) : tail); // 重叠部分
            }
            current.append(sentence); // 追加句子
        }

        String remaining = current.toString().strip(); // 处理剩余文本
        if (!remaining.isEmpty()) { // 有剩余内容
            if (!result.isEmpty() && remaining.length() < minChars / 2) { // 过短的尾部合并到前一块
                String last = result.remove(result.size() - 1);
                result.add((last + remaining).strip());
            } else {
                result.add(remaining); // 作为独立块
            }
        }

        if (result.isEmpty() && !text.isBlank()) { // 全部文本作为一个块
            result.add(text.strip());
        }
        return result; // 返回拆分结果
    }

    /**
     * 在父块文本中查找子块的起始偏移
     */
    private int findOffset(String parent, String child, int startFrom) {
        int idx = parent.indexOf(child, startFrom); // 从指定位置开始查找
        return idx >= 0 ? idx : 0; // 找不到返回 0
    }
}
