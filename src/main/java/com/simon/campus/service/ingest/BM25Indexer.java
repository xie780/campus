package com.simon.campus.service.ingest;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.JiebaSegmenter.SegMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * BM25 索引器：基于 Jieba 分词构建倒排索引，支持 BM25 稀疏检索
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BM25Indexer {

    private final JdbcTemplate jdbcTemplate; // JDBC 模板

    // BM25 constants
    private static final double K1 = 1.5; // BM25 参数 K1
    private static final double B  = 0.75; // BM25 参数 B

    private final JiebaSegmenter segmenter = new JiebaSegmenter(); // Jieba 分词器

    /**
     * 批量索引子块：构建倒排索引和文档统计
     */
    public void indexChildren(String docId, List<String> childIds, List<String> contents) {
        if (childIds.isEmpty()) return; // 空列表跳过

        double avgLen = contents.stream().mapToInt(String::length).average().orElse(100.0); // 计算平均文档长度

        List<Object[]> indexRows = new ArrayList<>(); // 倒排索引行
        List<Object[]> statsRows = new ArrayList<>(); // 文档统计行

        for (int i = 0; i < childIds.size(); i++) { // 遍历子块
            String childId = childIds.get(i);
            String content = contents.get(i);
            int docLen = content.length(); // 文档长度

            Map<String, Integer> tf = computeTF(content); // 计算词频
            for (Map.Entry<String, Integer> entry : tf.entrySet()) { // 遍历词频
                indexRows.add(new Object[]{childId, docId, entry.getKey(), entry.getValue(), docLen}); // 倒排索引行
            }
            statsRows.add(new Object[]{childId, docLen}); // 文档统计行
        }

        jdbcTemplate.batchUpdate( // 批量插入倒排索引
            "INSERT IGNORE INTO chunk_inverted_index (child_id, doc_id, term, tf, doc_len) VALUES (?,?,?,?,?)",
            indexRows
        );

        jdbcTemplate.batchUpdate( // 批量插入/更新文档统计
            "INSERT INTO chunk_stats (child_id, doc_len, hit_count) VALUES (?,?,0) " +
            "ON DUPLICATE KEY UPDATE doc_len = VALUES(doc_len)",
            statsRows
        );

        log.debug("Indexed {} child chunks from doc {}", childIds.size(), docId); // 记录索引完成
    }

    /**
     * BM25 搜索：返回子块 ID → 分数的映射，按分数降序取 topK
     */
    public List<Map.Entry<String, Double>> search(String query, int accessLevel, int topK) {
        List<String> queryTerms = tokenize(query); // 分词
        if (queryTerms.isEmpty()) return Collections.emptyList(); // 无词返回空

        long totalDocs = Optional.ofNullable( // 获取总文档数
            jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT child_id) FROM chunk_stats", Long.class)
        ).orElse(1L);
        double avgDocLen = Optional.ofNullable( // 获取平均文档长度
            jdbcTemplate.queryForObject("SELECT AVG(doc_len) FROM chunk_stats", Double.class)
        ).orElse(100.0);

        Map<String, Double> scores = new HashMap<>(); // 分数映射

        for (String term : queryTerms) { // 遍历查询词
            Long df = jdbcTemplate.queryForObject( // 获取文档频率
                "SELECT COUNT(DISTINCT child_id) FROM chunk_inverted_index WHERE term = ?",
                Long.class, term
            );
            if (df == null || df == 0) continue; // 无文档包含此词
            double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0); // 计算 IDF

            String sql = "SELECT i.child_id, i.tf, i.doc_len FROM chunk_inverted_index i " + // 查询词频（带可见性过滤）
                         "JOIN child_chunks c ON c.child_id = i.child_id " +
                         "WHERE i.term = ? AND c.access_level IN (" + accessPlaceholders(accessLevel) + ")";
            List<Object> params = new ArrayList<>(); // 参数列表
            params.add(term); // 查询词
            params.addAll(VisibilityPolicy.visibleLevelsForViewer(accessLevel)); // 可见级别
            jdbcTemplate.query(sql, rs -> { // 遍历结果
                String childId = rs.getString("child_id"); // 子块 ID
                double tf = rs.getDouble("tf"); // 词频
                double docLen = rs.getDouble("doc_len"); // 文档长度
                double tfNorm = (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * docLen / avgDocLen)); // TF 归一化
                scores.merge(childId, idf * tfNorm, Double::sum); // 累加 BM25 分数
            }, params.toArray());
        }

        return scores.entrySet().stream() // 按分数降序排序
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(topK) // 取 topK
            .toList();
    }

    /**
     * 删除文档的所有 BM25 索引
     */
    public void deleteByDocId(String docId) {
        List<String> childIds = jdbcTemplate.queryForList( // 获取子块 ID 列表
            "SELECT DISTINCT child_id FROM chunk_inverted_index WHERE doc_id = ?",
            String.class, docId
        );
        jdbcTemplate.update("DELETE FROM chunk_inverted_index WHERE doc_id = ?", docId); // 删除倒排索引
        if (!childIds.isEmpty()) { // 删除文档统计
            String placeholders = String.join(",", childIds.stream().map(id -> "?").toList());
            jdbcTemplate.update(
                "DELETE FROM chunk_stats WHERE child_id IN (" + placeholders + ")",
                childIds.toArray()
            );
        }
    }

    /**
     * 对文本进行 Jieba 分词（搜索模式，过滤短词）
     */
    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>(); // 分词结果
        for (var word : segmenter.process(text, SegMode.SEARCH)) { // Jieba 搜索模式分词
            String w = word.word.trim(); // 去空白
            if (w.length() >= 2) tokens.add(w); // 过滤单字词
        }
        return tokens; // 返回分词结果
    }

    /**
     * 计算文本的词频映射
     */
    private Map<String, Integer> computeTF(String content) {
        Map<String, Integer> tf = new HashMap<>(); // 词频映射
        for (var word : segmenter.process(content, SegMode.SEARCH)) { // Jieba 分词
            String w = word.word.trim(); // 去空白
            if (w.length() >= 2) tf.merge(w, 1, Integer::sum); // 过滤短词并计数
        }
        return tf; // 返回词频
    }

    /**
     * 生成访问级别占位符
     */
    private String accessPlaceholders(int accessLevel) {
        return String.join(",", VisibilityPolicy.visibleLevelsForViewer(accessLevel).stream().map(v -> "?").toList()); // 生成 ?,?,?
    }
}
