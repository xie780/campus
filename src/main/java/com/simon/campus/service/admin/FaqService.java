package com.simon.campus.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.simon.campus.mapper.FaqPairMapper;
import com.simon.campus.model.entity.FaqPair;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * FAQ 服务：管理常见问题对的增删改查、批量导入导出及命中计数
 */
@Service
@RequiredArgsConstructor
public class FaqService {

    private final FaqPairMapper faqPairMapper; // FAQ Mapper

    /**
     * 查询 FAQ 列表（支持关键词、分类、启用状态、优先级过滤）
     */
    public List<FaqPair> list(String keyword, String category, Integer enabled, String priority) {
        LambdaQueryWrapper<FaqPair> qw = new LambdaQueryWrapper<FaqPair>() // 构建查询条件
            .orderByDesc(FaqPair::getHitCount) // 按命中数倒序
            .orderByDesc(FaqPair::getCreatedAt); // 再按创建时间倒序
        if (StringUtils.hasText(keyword)) { // 关键词过滤
            qw.and(w -> w.like(FaqPair::getQuestion, keyword) // 匹配问题
                         .or().like(FaqPair::getKeywords, keyword)); // 或匹配关键词
        }
        if (StringUtils.hasText(category)) { // 分类过滤
            qw.eq(FaqPair::getCategory, category);
        }
        if (enabled != null) { // 启用状态过滤
            qw.eq(FaqPair::getEnabled, enabled);
        }
        if (StringUtils.hasText(priority)) { // 优先级过滤
            qw.eq(FaqPair::getPriority, priority);
        }
        return faqPairMapper.selectList(qw); // 返回查询结果
    }

    /**
     * 根据 ID 获取 FAQ
     */
    public FaqPair getById(Long id) {
        return faqPairMapper.selectById(id); // 返回指定 ID 的 FAQ
    }

    /**
     * 创建 FAQ
     */
    public FaqPair create(FaqPair faq) {
        faq.setId(null); // 清空 ID，由数据库自增
        if (faq.getHitCount() == null) faq.setHitCount(0); // 默认命中数为 0
        if (faq.getEnabled() == null) faq.setEnabled(1); // 默认启用
        if (faq.getPriority() == null) faq.setPriority("MEDIUM"); // 默认中等优先级
        faq.setCreatedAt(LocalDateTime.now()); // 设置创建时间
        faq.setUpdatedAt(LocalDateTime.now()); // 设置更新时间
        faqPairMapper.insert(faq); // 插入记录
        return faq; // 返回创建的 FAQ
    }

    /**
     * 更新 FAQ
     */
    public void update(Long id, FaqPair faq) {
        faq.setId(id); // 设置 ID
        faq.setUpdatedAt(LocalDateTime.now()); // 更新时间
        faqPairMapper.updateById(faq); // 更新记录
    }

    /**
     * 删除 FAQ
     */
    public void delete(Long id) {
        faqPairMapper.deleteById(id); // 删除指定 ID 的 FAQ
    }

    /**
     * 切换 FAQ 启用/禁用状态
     */
    public void toggleEnabled(Long id) {
        FaqPair faq = faqPairMapper.selectById(id); // 查询 FAQ
        if (faq != null) {
            faq.setEnabled(faq.getEnabled() == 1 ? 0 : 1); // 切换启用状态
            faq.setUpdatedAt(LocalDateTime.now()); // 更新时间
            faqPairMapper.updateById(faq); // 更新记录
        }
    }

    /**
     * 批量导入 FAQ
     */
    public void batchImport(List<FaqPair> faqs) {
        for (FaqPair faq : faqs) { // 遍历导入列表
            create(faq); // 逐条创建
        }
    }

    /**
     * 导出所有 FAQ
     */
    public List<FaqPair> exportAll() {
        return faqPairMapper.selectList(new LambdaQueryWrapper<FaqPair>() // 查询所有 FAQ
            .orderByDesc(FaqPair::getCreatedAt)); // 按创建时间倒序
    }

    /**
     * 递增 FAQ 命中计数
     */
    public void incrementHitCount(Long id) {
        faqPairMapper.update(null, // 原子递增命中计数
            new LambdaUpdateWrapper<FaqPair>()
                .eq(FaqPair::getId, id)
                .setSql("hit_count = hit_count + 1"));
    }

    /**
     * 获取热门 FAQ（按命中数排序，仅返回启用的）
     */
    public List<FaqPair> getTopFaqs(int n) {
        return faqPairMapper.selectList(new LambdaQueryWrapper<FaqPair>()
            .eq(FaqPair::getEnabled, 1) // 仅启用的
            .orderByDesc(FaqPair::getHitCount) // 按命中数倒序
            .last("LIMIT " + n)); // 限制返回数量
    }

    /**
     * 获取所有 FAQ 分类名称
     */
    public List<String> getCategories() {
        return faqPairMapper.selectList(new LambdaQueryWrapper<FaqPair>() // 查询分类字段
            .select(FaqPair::getCategory)
            .isNotNull(FaqPair::getCategory)
            .groupBy(FaqPair::getCategory))
            .stream()
            .map(FaqPair::getCategory) // 提取分类名称
            .filter(c -> c != null && !c.isBlank()) // 过滤空值
            .distinct() // 去重
            .toList();
    }
}
