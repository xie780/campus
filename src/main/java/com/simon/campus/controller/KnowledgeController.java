package com.simon.campus.controller;

import com.simon.campus.common.BizException;
import com.simon.campus.common.R;
import com.simon.campus.model.entity.KnowledgeCategory;
import com.simon.campus.model.entity.KnowledgeDoc;
import com.simon.campus.model.vo.DocVO;
import com.simon.campus.model.vo.SearchTestResultVO;
import com.simon.campus.service.ingest.DocumentPreview;
import com.simon.campus.service.ingest.KnowledgeService;
import com.simon.campus.service.ingest.VisibilityPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库控制器：处理文档上传/下载/预览/删除、分类管理、检索测试等知识库操作
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService; // 知识库服务

    public record CreateCategoryRequest(String name, String code) {} // 创建分类请求记录

    /**
     * 上传文档到知识库（仅教师和管理员）
     */
    @PostMapping("/docs/upload")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public R<DocVO> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam("title") String title,
        @RequestParam(value = "categoryCode", required = false) String categoryCode,
        @RequestParam(value = "accessLevel", defaultValue = "0") int accessLevel,
        Authentication auth
    ) throws Exception {
        if (file.isEmpty()) throw new BizException(400, "文件不能为空"); // 校验文件非空
        if (file.getSize() > 100L * 1024 * 1024) throw new BizException(400, "文件大小不能超过100MB"); // 校验文件大小
        if (!VisibilityPolicy.isValidDocumentLevel(accessLevel)) throw new BizException(400, "可见范围无效"); // 校验可见级别

        KnowledgeDoc doc = knowledgeService.upload(file, title, categoryCode, accessLevel, auth.getName()); // 调用服务上传文档
        DocVO vo = new DocVO(); // 构建返回 VO
        vo.setDocId(doc.getDocId()); // 设置文档 ID
        vo.setTitle(doc.getTitle()); // 设置标题
        vo.setFileName(doc.getFileName()); // 设置文件名
        vo.setStatus(doc.getStatus()); // 设置状态
        vo.setAccessLevel(doc.getAccessLevel()); // 设置可见级别
        vo.setAccessLevelName(DocVO.accessLevelName(doc.getAccessLevel())); // 设置可见级别名称
        return R.ok(vo); // 返回文档信息
    }

    /**
     * 获取文档列表（按分类和状态过滤，根据用户权限过滤可见范围）
     */
    @GetMapping("/docs")
    public R<List<DocVO>> list(
        @RequestParam(required = false) String categoryCode,
        @RequestParam(required = false) String status,
        Authentication auth
    ) {
        int userAccessLevel = resolveViewerLevel(auth); // 解析用户可见级别
        return R.ok(knowledgeService.listDocs(categoryCode, status, userAccessLevel)); // 返回文档列表
    }

    /**
     * 获取所有知识库分类
     */
    @GetMapping("/categories")
    public R<List<KnowledgeCategory>> categories() {
        return R.ok(knowledgeService.listCategories()); // 返回分类列表
    }

    /**
     * 创建知识库分类（仅教师和管理员）
     */
    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public R<KnowledgeCategory> createCategory(@RequestBody CreateCategoryRequest request) {
        if (request == null) throw new BizException(400, "请求不能为空"); // 校验请求非空
        return R.ok(knowledgeService.createCategory(request.name(), request.code())); // 创建并返回分类
    }

    /**
     * 删除知识库分类（仅教师和管理员）
     */
    @DeleteMapping("/categories/{code}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public R<Void> deleteCategory(@PathVariable String code) {
        knowledgeService.deleteCategory(code); // 删除指定编码的分类
        return R.ok();
    }

    /**
     * 预览文档（流式返回文件内容）
     */
    @GetMapping("/docs/{docId}/preview")
    public ResponseEntity<InputStreamResource> preview(@PathVariable String docId, Authentication auth) throws Exception {
        int userAccessLevel = resolveViewerLevel(auth); // 解析用户可见级别
        DocumentPreview preview = knowledgeService.previewDoc(docId, userAccessLevel); // 获取文档预览
        return ResponseEntity.ok() // 返回文件流
            .contentType(preview.mediaType())
            .header(HttpHeaders.CONTENT_DISPOSITION, preview.contentDisposition())
            .body(new InputStreamResource(preview.stream()));
    }

    /**
     * 下载文档
     */
    @GetMapping("/docs/{docId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String docId, Authentication auth) throws Exception {
        int userAccessLevel = resolveViewerLevel(auth); // 解析用户可见级别
        DocumentPreview download = knowledgeService.downloadDoc(docId, userAccessLevel); // 获取文档下载流
        return ResponseEntity.ok() // 返回文件下载流
            .contentType(download.mediaType())
            .header(HttpHeaders.CONTENT_DISPOSITION, download.contentDisposition())
            .body(new InputStreamResource(download.stream()));
    }

    /**
     * 预览文档纯文本内容
     */
    @GetMapping("/docs/{docId}/preview-text")
    public ResponseEntity<String> previewText(@PathVariable String docId, Authentication auth) throws Exception {
        int userAccessLevel = resolveViewerLevel(auth); // 解析用户可见级别
        return ResponseEntity.ok() // 返回纯文本内容
            .contentType(MediaType.TEXT_PLAIN)
            .body(knowledgeService.previewTextDoc(docId, userAccessLevel));
    }

    /**
     * 删除文档（仅教师和管理员）
     */
    @DeleteMapping("/docs/{docId}")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public R<Void> delete(@PathVariable String docId) {
        knowledgeService.deleteDoc(docId); // 删除指定文档
        return R.ok();
    }

    /**
     * 重新索引文档（仅教师和管理员）
     */
    @PostMapping("/docs/{docId}/reindex")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public R<DocVO> reindex(@PathVariable String docId) throws Exception {
        KnowledgeDoc doc = knowledgeService.reindexDoc(docId); // 重新索引文档
        DocVO vo = new DocVO(); // 构建返回 VO
        vo.setDocId(doc.getDocId()); // 设置文档 ID
        vo.setTitle(doc.getTitle()); // 设置标题
        vo.setFileName(doc.getFileName()); // 设置文件名
        vo.setStatus(doc.getStatus()); // 设置状态
        vo.setParentChunkCount(doc.getParentChunkCount()); // 设置父分块数
        vo.setChildChunkCount(doc.getChildChunkCount()); // 设置子分块数
        vo.setAccessLevel(doc.getAccessLevel()); // 设置可见级别
        vo.setAccessLevelName(DocVO.accessLevelName(doc.getAccessLevel())); // 设置可见级别名称
        return R.ok(vo); // 返回文档信息
    }

    /**
     * 检索测试接口（仅教师和管理员）
     */
    @GetMapping("/docs/search-test")
    @PreAuthorize("hasAnyRole('TEACHER','ADMIN')")
    public R<SearchTestResultVO> searchTest(
        @RequestParam String query,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int topK,
        Authentication auth
    ) throws Exception {
        int userAccessLevel = resolveViewerLevel(auth); // 解析用户可见级别
        return R.ok(knowledgeService.searchTest(query, userAccessLevel, topK)); // 返回检索测试结果
    }

    /**
     * 解析当前用户的可见级别（教师/管理员可见教师级别，学生仅可见学生级别）
     */
    private int resolveViewerLevel(Authentication auth) {
        if (auth == null) return VisibilityPolicy.STUDENT; // 未认证默认学生级别
        boolean teacher = auth.getAuthorities().stream() // 检查是否为教师或管理员
            .map(a -> a.getAuthority())
            .anyMatch(r -> r.equals("ROLE_TEACHER") || r.equals("ROLE_ADMIN"));
        return teacher ? VisibilityPolicy.TEACHER : VisibilityPolicy.STUDENT; // 返回对应可见级别
    }
}
