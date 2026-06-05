package com.simon.campus.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.simon.campus.common.BizException;
import com.simon.campus.mapper.ChildChunkMapper;
import com.simon.campus.mapper.KnowledgeCategoryMapper;
import com.simon.campus.mapper.KnowledgeDocMapper;
import com.simon.campus.mapper.ParentChunkMapper;
import com.simon.campus.model.entity.ChildChunk;
import com.simon.campus.model.entity.KnowledgeCategory;
import com.simon.campus.model.entity.KnowledgeDoc;
import com.simon.campus.model.entity.ParentChunk;
import com.simon.campus.model.vo.DocVO;
import com.simon.campus.model.vo.SearchTestResultVO;
import com.simon.campus.model.vo.SearchTestResultVO.HitItem;
import io.milvus.response.SearchResultsWrapper.IDScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库服务：管理知识分类、文档上传/删除/重索引/预览，以及搜索测试
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeService {

    private final KnowledgeDocMapper docMapper; // 文档 Mapper
    private final KnowledgeCategoryMapper categoryMapper; // 分类 Mapper
    private final ParentChunkMapper  parentMapper; // 父块 Mapper
    private final ChildChunkMapper   childMapper; // 子块 Mapper
    private final MinioService       minioService; // MinIO 存储服务
    private final EmbeddingService   embeddingService; // 向量嵌入服务
    private final BM25Indexer        bm25Indexer; // BM25 索引器
    private final MilvusService      milvusService; // Milvus 向量服务
    private final IngestAsyncService ingestAsyncService; // 异步入库服务
    private final DocumentParser     documentParser; // 文档解析器

    private static final int MAX_PREVIEW_TEXT_LENGTH = 100_000; // 预览文本最大长度

    /**
     * 获取所有启用的知识分类
     */
    public List<KnowledgeCategory> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<KnowledgeCategory>()
            .eq(KnowledgeCategory::getStatus, 1) // 仅启用的
            .orderByAsc(KnowledgeCategory::getSortOrder) // 按排序号
            .orderByAsc(KnowledgeCategory::getId)); // 再按 ID
    }

    /**
     * 创建知识分类
     */
    public KnowledgeCategory createCategory(String name, String code) {
        String cleanName = name == null ? "" : name.trim(); // 清理名称
        String cleanCode = code == null ? "" : code.trim(); // 清理编码
        if (cleanName.isBlank()) throw new BizException(400, "分类名称不能为空"); // 校验名称
        if (cleanName.length() > 100) throw new BizException(400, "分类名称不能超过100个字符"); // 校验长度
        if (!cleanCode.matches("^[a-z][a-z0-9_]{1,49}$")) { // 校验编码格式
            throw new BizException(400, "分类编码只能使用小写字母、数字和下划线，且需以字母开头");
        }

        KnowledgeCategory existing = categoryMapper.selectOne(new LambdaQueryWrapper<KnowledgeCategory>() // 检查编码唯一性
            .eq(KnowledgeCategory::getCode, cleanCode)
            .last("LIMIT 1"));
        if (existing != null) throw new BizException(400, "分类编码已存在"); // 编码重复

        int nextSortOrder = listCategories().stream() // 计算下一个排序号
            .map(KnowledgeCategory::getSortOrder)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .map(v -> v + 1)
            .orElse(100);

        KnowledgeCategory category = new KnowledgeCategory(); // 创建分类实体
        category.setName(cleanName); // 设置名称
        category.setCode(cleanCode); // 设置编码
        category.setStatus(1); // 启用状态
        category.setSortOrder(nextSortOrder); // 设置排序号
        categoryMapper.insert(category); // 插入记录
        return category; // 返回分类
    }

    /**
     * 删除知识分类（软删除）
     */
    public void deleteCategory(String code) {
        String cleanCode = code == null ? "" : code.trim(); // 清理编码
        if (cleanCode.isBlank()) throw new BizException(400, "分类编码不能为空"); // 校验编码

        KnowledgeCategory existing = categoryMapper.selectOne(new LambdaQueryWrapper<KnowledgeCategory>() // 查找分类
            .eq(KnowledgeCategory::getCode, cleanCode)
            .eq(KnowledgeCategory::getStatus, 1)
            .last("LIMIT 1"));
        if (existing == null) throw new BizException(404, "知识分类不存在"); // 分类不存在

        Long docCount = docMapper.selectCount(new LambdaQueryWrapper<KnowledgeDoc>() // 检查分类下是否有文档
            .eq(KnowledgeDoc::getCategoryCode, cleanCode));
        if (docCount != null && docCount > 0) { // 有文档则拒绝删除
            throw new BizException(400, "该分类下还有文档，请先删除文档或调整文档分类");
        }

        int rows = categoryMapper.update(null, new LambdaUpdateWrapper<KnowledgeCategory>() // 软删除
            .eq(KnowledgeCategory::getCode, cleanCode)
            .set(KnowledgeCategory::getStatus, 0)); // 设置状态为禁用
        log.info("[DELETE_FLOW] knowledge_category={} step=soft_delete rows={}", cleanCode, rows); // 记录删除日志
        if (rows <= 0) throw new BizException(500, "知识分类删除失败"); // 删除失败
    }

    // ── Upload & trigger async ingest ────────────────────────────────────────

    /**
     * 上传文档并触发异步入库管线
     */
    public KnowledgeDoc upload(MultipartFile file, String title, String categoryCode,
                               int accessLevel, String createdBy) throws Exception {
        KnowledgeDoc doc = new KnowledgeDoc(); // 创建文档实体
        doc.setDocId(UUID.randomUUID().toString()); // 生成文档 ID
        doc.setTitle(title); // 设置标题
        doc.setFileName(file.getOriginalFilename()); // 设置文件名
        String contentType = file.getContentType(); // 获取内容类型
        if (contentType != null && contentType.length() > 255) { // 截断过长的类型
            contentType = contentType.substring(0, 255);
        }
        doc.setFileType(contentType); // 设置文件类型
        doc.setFileSize(file.getSize()); // 设置文件大小
        doc.setCategoryCode(categoryCode); // 设置分类编码
        doc.setAccessLevel(accessLevel); // 设置访问级别
        doc.setStatus("PROCESSING"); // 初始状态为处理中
        doc.setCreatedBy(createdBy); // 设置创建人
        docMapper.insert(doc); // 插入文档记录

        String minioKey = minioService.upload(file, doc.getDocId()); // 上传到 MinIO
        doc.setMinioKey(minioKey); // 设置 MinIO 键
        docMapper.updateById(doc); // 更新文档记录

        byte[] bytes = file.getBytes(); // 获取文件字节
        ingestAsyncService.ingest(doc.getDocId(), bytes, file.getContentType()); // 触发异步入库

        return doc; // 返回文档
    }

    // ── List / Delete ─────────────────────────────────────────────────────────

    /**
     * 查询文档列表（按分类、状态过滤，带可见性控制）
     */
    public List<DocVO> listDocs(String categoryCode, String status, int userAccessLevel) {
        LambdaQueryWrapper<KnowledgeDoc> wrapper = new LambdaQueryWrapper<KnowledgeDoc>()
            .in(KnowledgeDoc::getAccessLevel, VisibilityPolicy.visibleLevelsForViewer(userAccessLevel)) // 可见性过滤
            .eq(categoryCode != null && !categoryCode.isBlank(), KnowledgeDoc::getCategoryCode, categoryCode) // 分类过滤
            .eq(status != null && !status.isBlank(), KnowledgeDoc::getStatus, status) // 状态过滤
            .orderByDesc(KnowledgeDoc::getCreatedAt); // 按创建时间倒序

        return docMapper.selectList(wrapper).stream() // 查询并转换
            .map(this::toDocVO)
            .collect(Collectors.toList());
    }

    /**
     * 删除文档（清理索引、MinIO 文件和数据库记录）
     */
    public void deleteDoc(String docId) {
        KnowledgeDoc doc = docMapper.selectById(docId); // 查询文档
        if (doc == null) throw new BizException(404, "文档不存在"); // 文档不存在

        log.info("[DELETE_FLOW] doc={} step=start title={} minioKey={}",
            docId, doc.getTitle(), doc.getMinioKey()); // 记录删除开始
        clearIndexes(docId); // 清理索引
        if (doc.getMinioKey() != null && !doc.getMinioKey().isBlank()) { // 删除 MinIO 文件
            try {
                minioService.deleteStrict(doc.getMinioKey());
            } catch (Exception e) {
                throw new BizException(500, "原始文件删除失败：" + e.getMessage()); // 删除失败
            }
        }
        int deletedDocs = docMapper.deleteById(docId); // 删除数据库记录
        log.info("[DELETE_FLOW] doc={} step=mysql_doc_delete rows={}", docId, deletedDocs); // 记录删除结果
    }

    /**
     * 重新索引文档
     */
    public KnowledgeDoc reindexDoc(String docId) throws Exception {
        KnowledgeDoc doc = docMapper.selectById(docId); // 查询文档
        if (doc == null) throw new BizException(404, "文档不存在"); // 文档不存在
        if (doc.getMinioKey() == null || doc.getMinioKey().isBlank()) { // 无原始文件
            throw new BizException(404, "原始文件不存在，无法重新索引");
        }

        byte[] bytes; // 从 MinIO 下载原始文件
        try (var inputStream = minioService.download(doc.getMinioKey())) {
            bytes = inputStream.readAllBytes(); // 读取全部字节
        }

        clearIndexes(docId); // 清理旧索引
        docMapper.updateProcessResult(docId, "PROCESSING", 0, 0, null); // 重置状态
        ingestAsyncService.ingest(docId, bytes, doc.getFileType()); // 触发重新入库

        doc.setStatus("PROCESSING"); // 更新状态
        doc.setParentChunkCount(0); // 重置块计数
        doc.setChildChunkCount(0);
        doc.setErrorMsg(null); // 清空错误消息
        return doc; // 返回文档
    }

    /**
     * 预览文档（内联显示）
     */
    public DocumentPreview previewDoc(String docId, int userAccessLevel) throws Exception {
        KnowledgeDoc doc = docMapper.selectById(docId); // 查询文档
        if (doc == null) throw new BizException(404, "文档不存在"); // 文档不存在
        if (!VisibilityPolicy.canView(doc.getAccessLevel(), userAccessLevel)) { // 无权限
            throw new BizException(403, "无权预览该文档");
        }
        if (doc.getMinioKey() == null || doc.getMinioKey().isBlank()) { // 无原始文件
            throw new BizException(404, "原始文件不存在");
        }
        return DocumentPreview.of( // 创建内联预览
            doc.getMinioKey(),
            doc.getFileName(),
            doc.getFileType(),
            minioService.download(doc.getMinioKey())
        );
    }

    /**
     * 下载文档（附件下载）
     */
    public DocumentPreview downloadDoc(String docId, int userAccessLevel) throws Exception {
        KnowledgeDoc doc = docMapper.selectById(docId); // 查询文档
        if (doc == null) throw new BizException(404, "文档不存在"); // 文档不存在
        if (!VisibilityPolicy.canView(doc.getAccessLevel(), userAccessLevel)) { // 无权限
            throw new BizException(403, "无权下载该文档");
        }
        if (doc.getMinioKey() == null || doc.getMinioKey().isBlank()) { // 无原始文件
            throw new BizException(404, "原始文件不存在");
        }
        return DocumentPreview.download( // 创建下载预览
            doc.getMinioKey(),
            doc.getFileName(),
            doc.getFileType(),
            minioService.download(doc.getMinioKey())
        );
    }

    /**
     * 预览文档的纯文本内容
     */
    public String previewTextDoc(String docId, int userAccessLevel) throws Exception {
        KnowledgeDoc doc = docMapper.selectById(docId); // 查询文档
        if (doc == null) throw new BizException(404, "文档不存在"); // 文档不存在
        if (!VisibilityPolicy.canView(doc.getAccessLevel(), userAccessLevel)) { // 无权限
            throw new BizException(403, "无权预览该文档");
        }
        if (doc.getMinioKey() == null || doc.getMinioKey().isBlank()) { // 无原始文件
            throw new BizException(404, "原始文件不存在");
        }

        try (var inputStream = minioService.download(doc.getMinioKey())) { // 下载文件
            String text = documentParser.parse(inputStream, doc.getFileType()).stream() // 解析文档
                .map(section -> section.heading() + "\n" + section.content()) // 拼接标题和内容
                .filter(sectionText -> !sectionText.isBlank()) // 过滤空章节
                .collect(Collectors.joining("\n\n")); // 合并
            if (text.length() > MAX_PREVIEW_TEXT_LENGTH) { // 超长截断
                return text.substring(0, MAX_PREVIEW_TEXT_LENGTH) + "\n\n[预览内容已截断]";
            }
            return text; // 返回文本
        }
    }

    // ── Search Test ──────────────────────────────────────────────────────────

    /**
     * 搜索测试：合并稠密向量检索和 BM25 稀疏检索结果
     */
    public SearchTestResultVO searchTest(String query, int userAccessLevel, int topK) throws Exception {
        float[] queryVec = embeddingService.embedOne(query); // 查询向量化
        List<IDScore> denseHits = milvusService.search(queryVec, userAccessLevel, topK); // 稠密检索

        List<Map.Entry<String, Double>> bm25Hits = bm25Indexer.search(query, userAccessLevel, topK); // BM25 检索

        List<HitItem> hits = new ArrayList<>(); // 合并结果
        Set<String> seen = new LinkedHashSet<>(); // 去重集合

        for (IDScore hit : denseHits) { // 遍历稠密结果
            String childId = hit.getStrID();
            if (seen.add(childId)) { // 未重复
                HitItem item = buildHitItem(childId, hit.getScore(), "dense", userAccessLevel); // 构建命中项
                if (item != null) hits.add(item); // 添加到结果
            }
        }
        for (Map.Entry<String, Double> entry : bm25Hits) { // 遍历 BM25 结果
            String childId = entry.getKey();
            if (seen.add(childId)) { // 未重复
                HitItem item = buildHitItem(childId, entry.getValue(), "bm25", userAccessLevel); // 构建命中项
                if (item != null) hits.add(item); // 添加到结果
            }
        }

        return SearchTestResultVO.builder() // 构建返回结果
            .query(query)
            .totalHits(hits.size())
            .hits(hits.stream().limit(topK).toList()) // 限制返回数量
            .build();
    }

    /**
     * 构建搜索命中项
     */
    private HitItem buildHitItem(String childId, double score, String source, int userAccessLevel) {
        ChildChunk child = childMapper.selectById(childId); // 查询子块
        if (child == null) { // 子块不存在
            return null;
        }
        if (!VisibilityPolicy.canView(child.getAccessLevel(), userAccessLevel)) return null; // 无权限
        return HitItem.builder() // 构建命中项
            .childId(childId)
            .parentId(child.getParentId())
            .docTitle(child.getDocTitle())
            .headingPath(child.getHeadingPath())
            .content(child.getContent())
            .score(score)
            .pageStart(child.getPageStart())
            .source(source)
            .build();
    }

    /**
     * 清理文档的所有索引（Milvus + BM25 + 数据库块）
     */
    private void clearIndexes(String docId) {
        milvusService.deleteByDocId(docId); // 删除 Milvus 向量
        bm25Indexer.deleteByDocId(docId); // 删除 BM25 索引
        log.info("[DELETE_FLOW] doc={} step=bm25_delete", docId); // 记录 BM25 删除
        int childRows = childMapper.delete(new LambdaQueryWrapper<ChildChunk>().eq(ChildChunk::getDocId, docId)); // 删除子块
        int parentRows = parentMapper.delete(new LambdaQueryWrapper<ParentChunk>().eq(ParentChunk::getDocId, docId)); // 删除父块
        log.info("[DELETE_FLOW] doc={} step=mysql_chunk_delete parentRows={} childRows={}",
            docId, parentRows, childRows); // 记录数据库删除
    }

    /**
     * 将文档实体转换为 VO
     */
    private DocVO toDocVO(KnowledgeDoc doc) {
        DocVO vo = new DocVO(); // 创建 VO
        vo.setDocId(doc.getDocId()); // 文档 ID
        vo.setTitle(doc.getTitle()); // 标题
        vo.setFileName(doc.getFileName()); // 文件名
        vo.setFileType(doc.getFileType()); // 文件类型
        vo.setFileSize(doc.getFileSize()); // 文件大小
        vo.setCategoryCode(doc.getCategoryCode()); // 分类编码
        vo.setStatus(doc.getStatus()); // 状态
        vo.setAccessLevel(doc.getAccessLevel()); // 访问级别
        vo.setAccessLevelName(DocVO.accessLevelName(doc.getAccessLevel())); // 访问级别名称
        vo.setParentChunkCount(doc.getParentChunkCount()); // 父块数
        vo.setChildChunkCount(doc.getChildChunkCount()); // 子块数
        vo.setErrorMsg(doc.getErrorMsg()); // 错误消息
        vo.setCreatedBy(doc.getCreatedBy()); // 创建人
        vo.setCreatedAt(doc.getCreatedAt()); // 创建时间
        vo.setUpdatedAt(doc.getUpdatedAt()); // 更新时间
        return vo; // 返回 VO
    }
}
