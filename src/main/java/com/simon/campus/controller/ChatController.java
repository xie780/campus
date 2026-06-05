package com.simon.campus.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.campus.common.BizException;
import com.simon.campus.common.R;
import com.simon.campus.mapper.ChatMessageMapper;
import com.simon.campus.mapper.ChatSessionMapper;
import com.simon.campus.model.entity.ChatMessage;
import com.simon.campus.model.entity.ChatSession;
import com.simon.campus.model.entity.HumanTicket;
import com.simon.campus.model.vo.ChatMessageVO;
import com.simon.campus.model.vo.SessionVO;
import com.simon.campus.service.admin.HumanHandoffService;
import com.simon.campus.service.agent.AgentOrchestrator;
import com.simon.campus.service.agent.ChatImageStorageService;
import com.simon.campus.service.agent.ChatSessionExportService;
import com.simon.campus.service.agent.VisionQuestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天控制器：处理对话流式推送、图片提问、会话管理、人工转接及消息持久化
 */
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final AgentOrchestrator orchestrator; // Agent 编排器，负责意图路由与回答生成
    private final ChatSessionMapper chatSessionMapper; // 会话表 Mapper
    private final ChatMessageMapper chatMessageMapper; // 消息表 Mapper
    private final HumanHandoffService handoffService; // 人工转接服务
    private final ChatImageStorageService chatImageStorageService; // 聊天图片存储服务
    private final ChatSessionExportService chatSessionExportService; // 会话导出服务
    private final VisionQuestionService visionQuestionService; // 视觉问答服务
    private final ObjectMapper objectMapper; // JSON 序列化工具

    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(); // SSE 异步线程池

    /**
     * SSE 流式对话接口：接收用户查询，通过 Agent 编排器流式返回回答
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam String query,
            @RequestParam(required = false) String sessionId) {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 获取当前认证信息
        if (!(auth.getCredentials() instanceof Long)) throw new BizException(401, "未授权，请提供有效 token"); // 校验凭证类型
        UserInfo user = new UserInfo( // 构建用户信息
            (Long) auth.getCredentials(), // 用户 ID
            (String) auth.getPrincipal(), // 用户名
            auth.getAuthorities().stream().findFirst() // 提取角色
                .map(a -> a.getAuthority().replace("ROLE_", "")).orElse("STUDENT")
        );
        String sid = resolveSessionId(sessionId, user.userId()); // 解析或生成会话 ID

        SseEmitter emitter = new SseEmitter(120_000L); // 创建 SSE 发射器，超时 120 秒

        sseExecutor.submit(() -> { // 异步执行流式处理
            try {
                HumanTicket openTicket = handoffService.findOpenTicket(sid); // 查找该会话是否有未关闭的人工工单
                if (openTicket != null) { // 若存在未关闭工单，走人工转接逻辑
                    handoffService.appendStudentMessage(sid, user.userId(), query); // 追加学生消息到工单
                    String ack = "你的补充内容已同步给老师，老师会在此会话中回复。"; // 构建确认提示
                    emitter.send(SseEmitter.event() // 发送确认 token
                        .name("token")
                        .data(Map.of("token", ack), MediaType.APPLICATION_JSON));
                    Map<String, Object> donePayload = new java.util.LinkedHashMap<>(); // 构建完成事件载荷
                    donePayload.put("sessionId", sid); // 会话 ID
                    donePayload.put("intent", "HUMAN_HANDOFF"); // 意图为人工转接
                    donePayload.put("handoff", true); // 标记为人工转接
                    donePayload.put("ticketId", openTicket.getId()); // 工单 ID
                    emitter.send(SseEmitter.event() // 发送完成事件
                        .name("done")
                        .data(donePayload, MediaType.APPLICATION_JSON));
                    emitter.complete(); // 结束 SSE
                    return;
                }

                AgentOrchestrator.OrchestrationResult result = orchestrator.handleStream( // 调用编排器进行流式处理
                    query, sid, user.userId(), user.username(), user.role(),
                    token -> { // 流式 token 回调
                        try {
                            emitter.send(SseEmitter.event() // 逐 token 推送给前端
                                .name("token")
                                .data(Map.of("token", token), MediaType.APPLICATION_JSON));
                        } catch (IOException e) {
                            throw new RuntimeException(e); // IO 异常转为运行时异常
                        }
                    }
                );

                persistMessages(sid, user.userId(), query, result); // 持久化用户消息和助手回复

                Map<String, Object> donePayload = new java.util.LinkedHashMap<>(); // 构建完成事件载荷
                donePayload.put("sessionId", sid); // 会话 ID
                donePayload.put("intent", result.getIntent()); // 识别的意图
                if (result.getToolResult() != null) { // 若有工具调用结果
                    donePayload.put("toolResult", result.getToolResult()); // 附带工具结果
                }
                donePayload.put("sourceRefs", result.getSourceRefs()); // 附带来源引用
                emitter.send(SseEmitter.event() // 发送完成事件
                    .name("done")
                    .data(donePayload, MediaType.APPLICATION_JSON));
                emitter.complete(); // 结束 SSE
            } catch (Exception e) { // 异常处理
                log.error("SSE stream error for session {}: {}", sid, e.getMessage()); // 记录错误日志
                try {
                    emitter.send(SseEmitter.event() // 发送错误事件
                        .name("error")
                        .data(Map.of("message", "处理请求时出现错误"), MediaType.APPLICATION_JSON));
                } catch (IOException ignored) {} // 忽略发送错误时的 IO 异常
                emitter.completeWithError(e); // 以错误结束 SSE
            }
        });

        return emitter; // 返回 SSE 发射器
    }

    /**
     * 图片提问接口：上传图片并结合文字问题进行问答
     */
    @PostMapping(value = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String, Object>> imageQuestion(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String sessionId,
            @RequestPart("image") MultipartFile image) throws Exception {

        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 获取当前认证信息
        if (!(auth.getCredentials() instanceof Long)) throw new BizException(401, "未授权，请提供有效 token"); // 校验凭证类型
        UserInfo user = new UserInfo( // 构建用户信息
            (Long) auth.getCredentials(), // 用户 ID
            (String) auth.getPrincipal(), // 用户名
            auth.getAuthorities().stream().findFirst() // 提取角色
                .map(a -> a.getAuthority().replace("ROLE_", "")).orElse("STUDENT")
        );
        if (image == null || image.isEmpty()) throw new BizException("请上传图片"); // 校验图片非空
        String contentType = image.getContentType(); // 获取图片 MIME 类型
        if (contentType == null || !contentType.startsWith("image/")) { // 校验是否为图片
            throw new BizException("仅支持上传图片文件");
        }
        if (image.getSize() > 8L * 1024 * 1024) { // 校验图片大小不超过 8MB
            throw new BizException("图片不能超过 8MB");
        }

        String sid = resolveSessionId(sessionId, user.userId()); // 解析或生成会话 ID
        String displayQuery = (query == null || query.isBlank()) ? "请分析这张图片" : query.strip(); // 处理查询文本
        ChatImageStorageService.StoredImage storedImage = chatImageStorageService.save( // 保存图片到存储
            image.getBytes(), contentType, image.getOriginalFilename()
        );
        String augmentedQuery = visionQuestionService.buildImageQuestion(displayQuery, image.getBytes(), contentType); // 构建增强查询（图片+文字）

        AgentOrchestrator.OrchestrationResult result = orchestrator.handleStream( // 调用编排器处理
            augmentedQuery, sid, user.userId(), user.username(), user.role(), token -> {}
        );
        persistMessages(sid, user.userId(), "[图片] " + displayQuery, result, storedImage); // 持久化消息（含图片信息）

        Map<String, Object> payload = new java.util.LinkedHashMap<>(); // 构建返回载荷
        payload.put("sessionId", sid); // 会话 ID
        payload.put("answer", result.getAnswer()); // 助手回答
        payload.put("intent", result.getIntent()); // 识别的意图
        payload.put("imageUrl", storedImage.url()); // 图片 URL
        payload.put("imageName", storedImage.originalName()); // 图片原始文件名
        payload.put("sourceRefs", result.getSourceRefs()); // 来源引用
        if (result.getToolResult() != null) { // 若有工具调用结果
            payload.put("toolResult", result.getToolResult()); // 附带工具结果
        }
        return R.ok(payload); // 返回结果
    }

    /**
     * 获取聊天图片：根据文件名加载已存储的图片
     */
    @GetMapping("/images/{fileName:.+}")
    public ResponseEntity<byte[]> getChatImage(@PathVariable String fileName) throws Exception {
        ChatImageStorageService.ImageResource image = chatImageStorageService.load(fileName); // 加载图片资源
        return ResponseEntity.ok() // 返回图片二进制流
            .contentType(MediaType.parseMediaType(image.contentType()))
            .body(image.bytes());
    }

    // ── Session management ────────────────────────────────────────────────────

    /**
     * 获取当前用户的所有会话列表
     */
    @GetMapping("/sessions")
    public R<List<SessionVO>> listSessions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 获取当前认证信息
        Long userId = (Long) auth.getCredentials(); // 提取用户 ID
        LambdaQueryWrapper<ChatSession> qw = new LambdaQueryWrapper<ChatSession>() // 构建查询条件：按用户 ID 过滤
            .eq(ChatSession::getUserId, userId)
            .orderByDesc(ChatSession::getUpdatedAt); // 按更新时间倒序
        List<SessionVO> sessions = chatSessionMapper.selectList(qw).stream() // 查询并转换为 VO
            .map(s -> SessionVO.builder()
                .sessionId(s.getSessionId())
                .title(s.getTitle())
                .status(s.getStatus())
                .messageCount(s.getMessageCount())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .build())
            .toList();
        return R.ok(sessions); // 返回会话列表
    }

    /**
     * 请求人工转接：将当前会话转交给人工客服
     */
    @PostMapping("/handoff")
    public R<Map<String, Object>> requestHandoff(@RequestBody HandoffRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 获取当前认证信息
        if (!(auth.getCredentials() instanceof Long)) throw new BizException(401, "未授权，请提供有效 token"); // 校验凭证类型
        Long userId = (Long) auth.getCredentials(); // 提取用户 ID
        String sid = resolveSessionId(request.sessionId(), userId); // 解析会话 ID
        HumanTicket ticket = handoffService.requestHandoff(sid, userId, request.summary(), request.urgency()); // 创建人工转接工单
        return R.ok(Map.of( // 返回工单信息
            "sessionId", sid, // 会话 ID
            "ticketId", ticket.getId(), // 工单 ID
            "ticketNo", ticket.getTicketNo(), // 工单编号
            "status", ticket.getStatus() // 工单状态
        ));
    }

    /**
     * 获取指定会话的所有消息
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public R<List<ChatMessageVO>> getMessages(@PathVariable String sessionId) {
        Long userId = currentUserId(); // 获取当前用户 ID
        requireOwnedSession(sessionId, userId); // 校验会话归属
        LambdaQueryWrapper<ChatMessage> qw = new LambdaQueryWrapper<ChatMessage>() // 按会话 ID 查询消息
            .eq(ChatMessage::getSessionId, sessionId)
            .orderByAsc(ChatMessage::getId); // 按消息 ID 升序
        List<ChatMessageVO> msgs = chatMessageMapper.selectList(qw).stream() // 查询并转换为 VO
            .map(m -> ChatMessageVO.builder()
                .id(m.getId())
                .role(m.getRole())
                .content(m.getContent())
                .intent(m.getIntent())
                .imageUrl(readImageMeta(m).imageUrl())
                .imageName(readImageMeta(m).imageName())
                .sourceRefs(readSourceRefs(m.getSourceRefs()))
                .createdAt(m.getCreatedAt())
                .build())
            .toList();
        return R.ok(msgs); // 返回消息列表
    }

    /**
     * 删除指定会话及其所有消息
     */
    @DeleteMapping("/sessions/{sessionId}")
    public R<Void> deleteSession(@PathVariable String sessionId) {
        Long userId = currentUserId(); // 获取当前用户 ID
        requireOwnedSession(sessionId, userId); // 校验会话归属
        chatSessionMapper.deleteById(sessionId); // 删除会话记录
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>() // 删除该会话下所有消息
            .eq(ChatMessage::getSessionId, sessionId));
        return R.ok(null);
    }

    /**
     * 导出指定会话为 Markdown 文件
     */
    @GetMapping(value = "/sessions/{sessionId}/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> exportSession(@PathVariable String sessionId) {
        Long userId = currentUserId(); // 获取当前用户 ID
        ChatSession session = requireOwnedSession(sessionId, userId); // 校验会话归属并获取会话
        List<ChatMessage> messages = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>() // 查询该会话所有消息
            .eq(ChatMessage::getSessionId, sessionId)
            .orderByAsc(ChatMessage::getId));
        String markdown = chatSessionExportService.toMarkdown(session, messages); // 转换为 Markdown
        String fileName = sanitizeFileName(session.getTitle()) + ".md"; // 生成文件名
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"); // URL 编码文件名
        return ResponseEntity.ok() // 返回 Markdown 文件下载响应
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
            .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
            .body(markdown);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * 持久化消息（无图片）
     */
    private void persistMessages(String sessionId, Long userId, String query,
                                  AgentOrchestrator.OrchestrationResult result) {
        persistMessages(sessionId, userId, query, result, null); // 委托给带图片参数的重载方法
    }

    /**
     * 持久化消息（含图片信息）：保存用户消息和助手回复到数据库
     */
    private void persistMessages(String sessionId, Long userId, String query,
                                  AgentOrchestrator.OrchestrationResult result,
                                  ChatImageStorageService.StoredImage image) {
        try {
            ChatSession existing = chatSessionMapper.selectById(sessionId); // 查询会话是否已存在
            if (existing == null) { // 若会话不存在则新建
                ChatSession session = new ChatSession();
                session.setSessionId(sessionId); // 设置会话 ID
                session.setUserId(userId); // 设置用户 ID
                session.setTitle(query.length() > 30 ? query.substring(0, 30) + "..." : query); // 截取标题
                session.setStatus("ACTIVE"); // 设置状态为活跃
                session.setMessageCount(0); // 初始消息数为 0
                session.setCreatedAt(LocalDateTime.now()); // 设置创建时间
                session.setUpdatedAt(LocalDateTime.now()); // 设置更新时间
                chatSessionMapper.insert(session); // 插入会话记录
            }

            ChatMessage userMsg = new ChatMessage(); // 构建用户消息
            userMsg.setSessionId(sessionId); // 关联会话 ID
            userMsg.setUserId(userId); // 设置用户 ID
            userMsg.setRole("user"); // 角色为用户
            userMsg.setContent(query); // 设置消息内容
            userMsg.setIntent(result.getIntent()); // 设置识别的意图
            if (image != null) { // 若有图片信息
                userMsg.setToolCalls(objectMapper.writeValueAsString(Map.of( // 将图片信息序列化为 JSON
                    "imageUrl", image.url(),
                    "imageName", image.originalName() == null ? "" : image.originalName()
                )));
            }
            userMsg.setCreatedAt(LocalDateTime.now()); // 设置创建时间
            chatMessageMapper.insert(userMsg); // 插入用户消息

            ChatMessage assistantMsg = new ChatMessage(); // 构建助手消息
            assistantMsg.setSessionId(sessionId); // 关联会话 ID
            assistantMsg.setUserId(userId); // 设置用户 ID
            assistantMsg.setRole("assistant"); // 角色为助手
            assistantMsg.setContent(result.getAnswer()); // 设置回答内容
            assistantMsg.setIntent(result.getIntent()); // 设置识别的意图
            if (!result.getSourceRefs().isEmpty()) { // 若有来源引用
                assistantMsg.setSourceRefs(objectMapper.writeValueAsString(result.getSourceRefs())); // 序列化来源引用
            }
            assistantMsg.setCreatedAt(LocalDateTime.now()); // 设置创建时间
            chatMessageMapper.insert(assistantMsg); // 插入助手消息

            chatSessionMapper.update(null, // 更新会话的消息计数和更新时间
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ChatSession>()
                    .eq(ChatSession::getSessionId, sessionId)
                    .setSql("message_count = message_count + 2, updated_at = NOW()"));
        } catch (Exception e) {
            log.warn("Failed to persist messages: {}", e.getMessage()); // 记录持久化失败警告
        }
    }

    /**
     * 解析来源引用 JSON 字符串为 SourceRefVO 列表
     */
    private List<ChatMessageVO.SourceRefVO> readSourceRefs(String sourceRefs) {
        if (sourceRefs == null || sourceRefs.isBlank()) { // 空值检查
            return List.of();
        }
        try {
            var root = objectMapper.readTree(sourceRefs); // 解析 JSON
            if (!root.isArray()) { // 非数组则返回空列表
                return List.of();
            }
            List<ChatMessageVO.SourceRefVO> refs = new java.util.ArrayList<>(); // 构建结果列表
            for (var item : root) { // 遍历每个引用项
                refs.add(ChatMessageVO.SourceRefVO.builder()
                    .docTitle(item.path("docTitle").asText("")) // 文档标题
                    .headingPath(item.path("headingPath").asText(null)) // 标题路径
                    .pageStart(item.path("pageStart").isMissingNode() || item.path("pageStart").isNull() // 页码起始
                        ? null : item.path("pageStart").asInt())
                    .build());
            }
            return refs; // 返回来源引用列表
        } catch (Exception e) {
            log.warn("Failed to parse source refs: {}", e.getMessage()); // 记录解析失败警告
            return List.of();
        }
    }

    /**
     * 从消息的 toolCalls 字段中读取图片元信息
     */
    private ImageMeta readImageMeta(ChatMessage message) {
        if (message.getToolCalls() == null || message.getToolCalls().isBlank()) { // 空值检查
            return new ImageMeta(null, null);
        }
        try {
            var root = objectMapper.readTree(message.getToolCalls()); // 解析 JSON
            return new ImageMeta( // 提取图片 URL 和名称
                root.path("imageUrl").asText(null),
                root.path("imageName").asText(null)
            );
        } catch (Exception e) {
            return new ImageMeta(null, null); // 解析失败返回空元信息
        }
    }

    /**
     * 解析会话 ID：若为空则生成新的 UUID
     */
    private String resolveSessionId(String sessionId, Long userId) {
        if (sessionId != null && !sessionId.isBlank()) return sessionId; // 已有会话 ID 则直接使用
        return UUID.randomUUID().toString().replace("-", ""); // 生成新会话 ID
    }

    /**
     * 获取当前登录用户 ID
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication(); // 获取认证信息
        if (!(auth.getCredentials() instanceof Long userId)) throw new BizException(401, "未授权，请提供有效 token"); // 校验凭证
        return userId; // 返回用户 ID
    }

    /**
     * 校验会话归属：确保会话存在且属于当前用户
     */
    private ChatSession requireOwnedSession(String sessionId, Long userId) {
        ChatSession session = chatSessionMapper.selectById(sessionId); // 查询会话
        if (session == null) throw new BizException(404, "会话不存在"); // 会话不存在则抛出异常
        if (!userId.equals(session.getUserId())) throw new BizException(403, "无权访问该会话"); // 非本人会话则抛出异常
        return session; // 返回会话实体
    }

    private String sanitizeFileName(String title) {
        String value = title == null || title.isBlank() ? "聊天记录" : title; // 空标题使用默认值
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").strip(); // 移除非法文件名字符
    }

    private record UserInfo(Long userId, String username, String role) {} // 用户信息内部记录
    private record HandoffRequest(String sessionId, String summary, String urgency) {} // 人工转接请求记录
    private record ImageMeta(String imageUrl, String imageName) {} // 图片元信息记录
}
