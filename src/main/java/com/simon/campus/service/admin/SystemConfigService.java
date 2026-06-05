package com.simon.campus.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.simon.campus.mapper.SystemConfigMapper;
import com.simon.campus.model.entity.SystemConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统配置服务：提供数据库持久化 + 内存缓存的双层配置管理
 * 启动时从数据库加载配置，合并 yml 默认值；运行时以数据库为准
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigService {

    private final SystemConfigMapper mapper; // 配置 Mapper
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>(); // 内存缓存

    // Defaults injected from application.yml
    @Value("${models.intent-router.model:qwen-turbo}")      private String defaultIntentModel; // 默认意图路由模型
    @Value("${models.query-rewriter.model:qwen-plus}")      private String defaultRewriteModel; // 默认查询改写模型
    @Value("${models.rag-generator.model:qwen-max}")        private String defaultRagModel; // 默认 RAG 生成模型
    @Value("${models.tool-caller.model:qwen-plus}")         private String defaultToolModel; // 默认工具调用模型
    @Value("${models.chitchat.model:qwen-turbo}")           private String defaultChitchatModel; // 默认闲聊模型
    @Value("${models.intent-router.temperature:0.1}")       private String defaultIntentTemp; // 默认意图路由温度
    @Value("${models.intent-router.max-tokens:16}")         private String defaultIntentMaxTokens; // 默认意图路由最大 Token
    @Value("${models.query-rewriter.temperature:0.2}")      private String defaultRewriteTemp; // 默认查询改写温度
    @Value("${models.query-rewriter.max-tokens:512}")       private String defaultRewriteMaxTokens; // 默认查询改写最大 Token
    @Value("${models.rag-generator.temperature:0.2}")       private String defaultRagTemp; // 默认 RAG 生成温度
    @Value("${models.rag-generator.max-tokens:2048}")       private String defaultRagMaxTokens; // 默认 RAG 最大 Token
    @Value("${models.tool-caller.temperature:0.1}")         private String defaultToolTemp; // 默认工具调用温度
    @Value("${models.tool-caller.max-tokens:1024}")         private String defaultToolMaxTokens; // 默认工具调用最大 Token
    @Value("${models.chitchat.temperature:0.7}")            private String defaultChitchatTemp; // 默认闲聊温度
    @Value("${models.chitchat.max-tokens:1024}")            private String defaultChitchatMaxTokens; // 默认闲聊最大 Token

    /**
     * 初始化：加载默认值到缓存，从数据库加载配置并合并，持久化缺失的默认值
     */
    @PostConstruct
    public void init() {
        Map<String, String[]> defaults = buildDefaults(); // 构建默认配置映射
        defaults.forEach((k, v) -> cache.putIfAbsent(k, v[0])); // 先用编译期默认值填充缓存

        try {
            mapper.selectList(null).forEach(c -> cache.put(c.getConfigKey(), c.getConfigValue())); // 加载数据库配置覆盖默认值

            for (Map.Entry<String, String[]> entry : defaults.entrySet()) { // 持久化缺失的默认值到数据库
                String key = entry.getKey();
                String[] valueDesc = entry.getValue();
                if (mapper.selectCount(new LambdaQueryWrapper<SystemConfig>() // 检查是否已存在
                        .eq(SystemConfig::getConfigKey, key)) == 0) {
                    SystemConfig cfg = new SystemConfig();
                    cfg.setConfigKey(key); // 设置配置键
                    cfg.setConfigValue(valueDesc[0]); // 设置配置值
                    cfg.setConfigType(valueDesc.length > 2 ? valueDesc[2] : "STRING"); // 设置类型
                    cfg.setDescription(valueDesc[1]); // 设置描述
                    cfg.setUpdatedAt(LocalDateTime.now()); // 设置更新时间
                    try {
                        mapper.insert(cfg); // 插入记录
                    } catch (Exception e) {
                        log.debug("Config {} already exists", key); // 已存在则忽略
                    }
                }
            }
            log.info("SystemConfigService initialized from DB with {} configs", cache.size()); // 记录初始化完成
        } catch (Exception e) { // 数据库不可用时的降级处理
            log.warn("DB unavailable at startup, using compiled defaults ({} configs). Will retry on next request.",
                cache.size());
        }
    }

    /**
     * 获取字符串配置（带默认值）
     */
    public String get(String key, String defaultVal) {
        return cache.getOrDefault(key, defaultVal); // 从缓存获取或返回默认值
    }

    /**
     * 获取布尔配置（带默认值）
     */
    public boolean getBool(String key, boolean defaultVal) {
        String val = cache.get(key); // 从缓存获取
        if (val == null) return defaultVal; // 空值返回默认值
        return "true".equalsIgnoreCase(val) || "1".equals(val); // 解析布尔值
    }

    /**
     * 获取浮点数配置（带默认值）
     */
    public double getDouble(String key, double defaultVal) {
        String val = cache.get(key); // 从缓存获取
        if (val == null) return defaultVal; // 空值返回默认值
        try { return Double.parseDouble(val); } catch (Exception e) { return defaultVal; } // 解析失败返回默认值
    }

    /**
     * 获取整数配置（带默认值）
     */
    public int getInt(String key, int defaultVal) {
        String val = cache.get(key); // 从缓存获取
        if (val == null) return defaultVal; // 空值返回默认值
        try { return Integer.parseInt(val); } catch (Exception e) { return defaultVal; } // 解析失败返回默认值
    }

    /**
     * 获取所有配置列表
     */
    public List<SystemConfig> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<SystemConfig>() // 查询所有配置
            .orderByAsc(SystemConfig::getConfigKey)); // 按键排序
    }

    /**
     * 获取分组后的配置详情（包含值、类型、描述、更新信息）
     */
    public Map<String, Object> listGrouped() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (SystemConfig c : listAll()) { // 遍历所有配置
            result.put(c.getConfigKey(), Map.of( // 构建配置详情
                "value", c.getConfigValue() != null ? c.getConfigValue() : "", // 值
                "type", c.getConfigType() != null ? c.getConfigType() : "STRING", // 类型
                "description", c.getDescription() != null ? c.getDescription() : "", // 描述
                "updatedBy", c.getUpdatedBy() != null ? c.getUpdatedBy() : 0L, // 更新人
                "updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : "" // 更新时间
            ));
        }
        return result; // 返回分组结果
    }

    /**
     * 更新单个配置
     */
    public void update(String key, String value, Long updatedBy) {
        cache.put(key, value); // 更新内存缓存
        LambdaQueryWrapper<SystemConfig> qw = new LambdaQueryWrapper<SystemConfig>()
            .eq(SystemConfig::getConfigKey, key);
        SystemConfig existing = mapper.selectOne(qw); // 查询现有配置
        if (existing != null) {
            mapper.update(null, new LambdaUpdateWrapper<SystemConfig>() // 更新已有配置
                .eq(SystemConfig::getConfigKey, key)
                .set(SystemConfig::getConfigValue, value)
                .set(SystemConfig::getUpdatedBy, updatedBy)
                .set(SystemConfig::getUpdatedAt, LocalDateTime.now()));
        } else {
            SystemConfig cfg = new SystemConfig(); // 创建新配置
            cfg.setConfigKey(key); // 设置键
            cfg.setConfigValue(value); // 设置值
            cfg.setConfigType("STRING"); // 设置类型
            cfg.setUpdatedBy(updatedBy); // 设置更新人
            cfg.setUpdatedAt(LocalDateTime.now()); // 设置时间
            mapper.insert(cfg); // 插入记录
        }
    }

    /**
     * 批量更新配置
     */
    public void batchUpdate(Map<String, String> updates, Long updatedBy) {
        updates.forEach((k, v) -> update(k, v, updatedBy)); // 逐条更新
    }

    /**
     * 导出所有配置
     */
    public Map<String, String> export() {
        return new LinkedHashMap<>(cache); // 返回缓存副本
    }

    /**
     * 导入配置
     */
    public void importConfigs(Map<String, String> configs, Long updatedBy) {
        configs.forEach((k, v) -> update(k, v, updatedBy)); // 逐条导入
    }

    /**
     * 重置所有配置为默认值
     */
    public void resetDefaults(Long updatedBy) {
        buildDefaults().forEach((key, valueDesc) -> update(key, valueDesc[0], updatedBy)); // 重置每个配置项
    }

    /**
     * 构建默认配置映射：包含所有可配置项及其默认值、描述和类型
     */
    private Map<String, String[]> buildDefaults() {
        Map<String, String[]> d = new LinkedHashMap<>();
        // Model configs
        d.put("models.intent-router.model",      new String[]{defaultIntentModel,   "意图路由模型"});
        d.put("models.intent-router.temperature",new String[]{defaultIntentTemp,    "意图路由温度", "NUMBER"});
        d.put("models.intent-router.max-tokens", new String[]{defaultIntentMaxTokens,"意图路由最大Token", "NUMBER"});
        d.put("models.query-rewriter.model",     new String[]{defaultRewriteModel,  "查询改写模型"});
        d.put("models.query-rewriter.temperature",new String[]{defaultRewriteTemp,  "查询改写温度", "NUMBER"});
        d.put("models.query-rewriter.max-tokens", new String[]{defaultRewriteMaxTokens, "查询改写最大Token", "NUMBER"});
        d.put("models.rag-generator.model",      new String[]{defaultRagModel,      "RAG生成模型"});
        d.put("models.rag-generator.temperature",new String[]{defaultRagTemp,       "RAG生成温度", "NUMBER"});
        d.put("models.rag-generator.max-tokens", new String[]{defaultRagMaxTokens,  "RAG最大Token", "NUMBER"});
        d.put("models.tool-caller.model",        new String[]{defaultToolModel,     "工具调用模型"});
        d.put("models.tool-caller.temperature",  new String[]{defaultToolTemp,      "工具调用温度", "NUMBER"});
        d.put("models.tool-caller.max-tokens",   new String[]{defaultToolMaxTokens, "工具调用最大Token", "NUMBER"});
        d.put("models.chitchat.model",           new String[]{defaultChitchatModel, "闲聊模型"});
        d.put("models.chitchat.temperature",     new String[]{defaultChitchatTemp,  "闲聊温度", "NUMBER"});
        d.put("models.chitchat.max-tokens",      new String[]{defaultChitchatMaxTokens, "闲聊最大Token", "NUMBER"});
        // Prompt templates
        d.put("prompt.rag_default", new String[]{
            "你是SmartCampus校园智能助手。请根据参考资料回答问题，标注来源[来源: 文档名, 第X页]。",
            "RAG 默认 Prompt"});
        d.put("prompt.academic_default", new String[]{
            "你是SmartCampus校园智能助手，擅长查询教务信息。请使用工具获取准确数据后回答。",
            "教务工具 Prompt"});
        d.put("prompt.chitchat_default", new String[]{
            "你是SmartCampus校园智能助手，友好专业。日常闲聊轻松回应，校园问题引导使用功能查询。",
            "闲聊 Prompt"});
        // Tool switches
        d.put("tool.query_academic_calendar.enabled",  new String[]{"true",  "校历查询工具开关", "BOOLEAN"});
        d.put("tool.query_course_selection.enabled",   new String[]{"true",  "选课查询工具开关", "BOOLEAN"});
        d.put("tool.query_department_contact.enabled", new String[]{"true",  "部门联系工具开关", "BOOLEAN"});
        d.put("tool.create_human_ticket.enabled",      new String[]{"true",  "转人工工单工具开关", "BOOLEAN"});
        // RAG params
        d.put("rag.topk.child",          new String[]{"20",   "Child召回数", "NUMBER"});
        d.put("rag.topk.child_sub",      new String[]{"7",    "子问题Child召回数", "NUMBER"});
        d.put("rag.topk.parent",         new String[]{"5",    "Parent回捞数", "NUMBER"});
        d.put("rag.rerank.score_thresh", new String[]{"0.3",  "Rerank最低分", "NUMBER"});
        d.put("rag.rerank.top_n",        new String[]{"12",   "Rerank保留数量", "NUMBER"});
        d.put("faq.match.exact_thresh",  new String[]{"0.92", "FAQ精确命中阈值", "NUMBER"});
        d.put("faq.match.candidate_thresh", new String[]{"0.85", "FAQ候选命中阈值", "NUMBER"});
        return d; // 返回默认配置映射
    }
}
