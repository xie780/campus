package com.simon.campus.service.ingest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.campus.mapper.ChildChunkMapper;
import com.simon.campus.mapper.KnowledgeDocMapper;
import com.simon.campus.mapper.ParentChunkMapper;
import com.simon.campus.model.entity.ChildChunk;
import com.simon.campus.model.entity.KnowledgeDoc;
import com.simon.campus.model.entity.ParentChunk;
import com.simon.campus.service.ingest.DocumentParser.ParsedSection;
import com.simon.campus.service.ingest.ParentChildChunkSplitter.SplitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 异步入库服务：在独立线程池中执行文档解析、分块、向量化、索引写入的完整管线
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestAsyncService {

    private final KnowledgeDocMapper docMapper; // 文档 Mapper
    private final ParentChunkMapper  parentMapper; // 父块 Mapper
    private final ChildChunkMapper   childMapper; // 子块 Mapper
    private final DocumentParser     documentParser; // 文档解析器
    private final ParentChildChunkSplitter splitter; // 分块拆分器
    private final EmbeddingService   embeddingService; // 向量嵌入服务
    private final BM25Indexer        bm25Indexer; // BM25 索引器
    private final MilvusService      milvusService; // Milvus 向量服务

    private static final int MILVUS_BATCH = 20; // Milvus 批量插入大小

    /**
     * 异步执行文档入库管线：解析 → 分块 → BM25 索引 → 向量化 → Milvus 插入
     */
    @Async("ingestExecutor")
    public void ingest(String docId, byte[] fileBytes, String contentType) {
        try {
            log.info("Ingest started for doc {}", docId); // 记录开始
            KnowledgeDoc doc = docMapper.selectById(docId); // 查询文档记录
            log.info("[INGEST_FLOW] doc={} step=start title={} contentType={} bytes={}",
                docId, doc != null ? doc.getTitle() : "", contentType, fileBytes != null ? fileBytes.length : 0); // 记录入库流程

            List<ParsedSection> sections; // 解析后的章节列表
            try (ByteArrayInputStream is = new ByteArrayInputStream(fileBytes)) {
                sections = documentParser.parseForIngest(is, contentType); // 解析文档（含视觉解析）
            }
            log.info("[INGEST_FLOW] doc={} step=parse sections={}", docId, sections.size()); // 记录解析结果
            if (sections.isEmpty()) { // 无内容
                throw new IllegalStateException("文档未解析出可入库内容，请确认文件是否包含可识别文本或已配置视觉解析能力");
            }

            SplitResult split = splitter.split(docId, doc.getTitle(), doc.getAccessLevel(), sections); // 分块拆分
            List<ParentChunk> parents  = split.parents(); // 父块列表
            List<ChildChunk>  children = split.children(); // 子块列表
            log.info("[INGEST_FLOW] doc={} step=split parents={} children={}",
                docId, parents.size(), children.size()); // 记录分块结果

            children.forEach(c -> c.setCategory(doc.getCategoryCode())); // 设置子块分类
            parents.forEach(p -> p.setCategory(doc.getCategoryCode())); // 设置父块分类

            if (!parents.isEmpty()) parentMapper.insertBatch(parents); // 批量插入父块
            if (!children.isEmpty()) childMapper.insertBatch(children); // 批量插入子块
            log.info("[INGEST_FLOW] doc={} step=mysql_insert parents={} children={}",
                docId, parents.size(), children.size()); // 记录数据库插入

            List<String> childIds   = children.stream().map(ChildChunk::getChildId).toList(); // 子块 ID 列表
            List<String> childTexts = children.stream().map(ChildChunk::getContent).toList(); // 子块文本列表
            bm25Indexer.indexChildren(docId, childIds, childTexts); // BM25 索引
            log.info("[INGEST_FLOW] doc={} step=bm25_index children={}", docId, childIds.size()); // 记录 BM25 索引

            for (int i = 0; i < children.size(); i += MILVUS_BATCH) { // 分批向量化并插入 Milvus
                List<ChildChunk> batch = children.subList(i, Math.min(i + MILVUS_BATCH, children.size())); // 当前批次
                List<String> texts = batch.stream().map(ChildChunk::getContent).toList(); // 文本列表
                List<float[]> embeddings = embeddingService.embedBatch(texts); // 向量化
                milvusService.insertBatch(batch, embeddings); // 插入 Milvus
                log.info("[INGEST_FLOW] doc={} step=milvus_insert batchStart={} batchSize={}",
                    docId, i, batch.size()); // 记录 Milvus 插入
            }

            docMapper.updateProcessResult(docId, "READY", parents.size(), children.size(), null); // 更新状态为就绪
            log.info("Ingest completed for doc {} — {} parents, {} children", docId, parents.size(), children.size()); // 记录完成

        } catch (Throwable e) { // 异常处理
            if (e instanceof VirtualMachineError virtualMachineError) { // 虚拟机错误直接抛出
                throw virtualMachineError;
            }
            if (e instanceof ThreadDeath threadDeath) { // 线程终止直接抛出
                throw threadDeath;
            }
            log.error("Ingest failed for doc {}: {}", docId, e.getMessage(), e); // 记录错误
            try {
                docMapper.updateProcessResult(docId, "FAILED", 0, 0, IngestErrorMessage.from(e)); // 更新状态为失败
            } catch (Exception updateError) { // 更新失败也记录
                log.error("Failed to mark ingest doc {} as FAILED: {}", docId, updateError.getMessage(), updateError);
            }
        }
    }
}
