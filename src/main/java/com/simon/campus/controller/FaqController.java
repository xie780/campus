package com.simon.campus.controller;

import com.simon.campus.common.R;
import com.simon.campus.model.entity.FaqPair;
import com.simon.campus.service.admin.FaqService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * FAQ 控制器：提供常见问题对的增删改查、分类管理及批量导入导出
 */
@RestController
@RequestMapping("/api/v1/faq")
@RequiredArgsConstructor
public class FaqController {

    private final FaqService faqService; // FAQ 服务

    /**
     * 查询 FAQ 列表（支持关键词、分类、启用状态、优先级过滤）
     */
    @GetMapping
    public R<List<FaqPair>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer enabled,
            @RequestParam(required = false) String priority) {
        return R.ok(faqService.list(keyword, category, enabled, priority)); // 返回过滤后的 FAQ 列表
    }

    /**
     * 获取所有 FAQ 分类
     */
    @GetMapping("/categories")
    public R<List<String>> categories() {
        return R.ok(faqService.getCategories()); // 返回分类名称列表
    }

    /**
     * 获取热门 FAQ（按访问量排序）
     */
    @GetMapping("/top")
    public R<List<FaqPair>> top(@RequestParam(defaultValue = "10") int n) {
        return R.ok(faqService.getTopFaqs(n)); // 返回前 N 条热门 FAQ
    }

    /**
     * 根据 ID 获取单条 FAQ
     */
    @GetMapping("/{id}")
    public R<FaqPair> getById(@PathVariable Long id) {
        return R.ok(faqService.getById(id)); // 返回指定 ID 的 FAQ
    }

    /**
     * 创建 FAQ（仅教师和管理员）
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<FaqPair> create(@RequestBody FaqPair faq) {
        return R.ok(faqService.create(faq)); // 创建并返回新 FAQ
    }

    /**
     * 更新 FAQ（仅教师和管理员）
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> update(@PathVariable Long id, @RequestBody FaqPair faq) {
        faqService.update(id, faq); // 更新指定 ID 的 FAQ
        return R.ok(null);
    }

    /**
     * 删除 FAQ（仅教师和管理员）
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> delete(@PathVariable Long id) {
        faqService.delete(id); // 删除指定 ID 的 FAQ
        return R.ok(null);
    }

    /**
     * 切换 FAQ 启用/禁用状态（仅教师和管理员）
     */
    @PutMapping("/{id}/toggle")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> toggle(@PathVariable Long id) {
        faqService.toggleEnabled(id); // 切换启用状态
        return R.ok(null);
    }

    /**
     * 批量导入 FAQ（仅教师和管理员）
     */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<Void> batchImport(@RequestBody List<FaqPair> faqs) {
        faqService.batchImport(faqs); // 批量导入 FAQ 列表
        return R.ok(null);
    }

    /**
     * 导出所有 FAQ（仅教师和管理员）
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','TEACHER')")
    public R<List<FaqPair>> export() {
        return R.ok(faqService.exportAll()); // 返回全部 FAQ 列表
    }
}
