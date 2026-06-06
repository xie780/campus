package com.simon.campus.service.tool;

import com.simon.campus.model.entity.HumanTicket;
import com.simon.campus.service.admin.HumanHandoffService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 人工客服工单工具：创建人工服务工单，将用户问题转接给老师处理
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HumanTicketTool {

    private final HumanHandoffService handoffService;

    /**
     * Spring AI 工具方法：创建人工客服工单
     */
    @Tool(description = "创建人工客服工单，转接给老师处理")
    public ToolResult createHumanTicket(
            @ToolParam(description = "问题摘要，描述用户的问题或诉求") String summary,
            @ToolParam(description = "紧急程度：HIGH/MEDIUM/LOW") String urgency) {
        ToolResult result = create(null, null, summary, urgency);
        ToolResultCapture.set(result);
        return result;
    }

    /**
     * 创建人工客服工单：解析紧急程度，调用转接服务创建工单
     */
    public ToolResult create(String sessionId, Long userId, String summary, String urgency) {
        try {
            String resolvedUrgency = "MEDIUM";
            if (urgency != null) {
                switch (urgency.toLowerCase()) {
                    case "high":
                    case "高":
                    case "紧急":
                        resolvedUrgency = "HIGH";
                        break;
                    case "low":
                    case "低":
                        resolvedUrgency = "LOW";
                        break;
                    default:
                        resolvedUrgency = "MEDIUM";
                        break;
                }
            }

            HumanTicket ticket = handoffService.requestHandoff(sessionId, userId, summary, resolvedUrgency, false); // 创建工单

            return ToolResult.builder() // 构建成功结果
                .success(true)
                .toolName("create_human_ticket")
                .params(Map.of("session_id", sessionId != null ? sessionId : "", // 会话 ID
                               "urgency", resolvedUrgency)) // 紧急程度
                .data(Map.of("ticketNo", ticket.getTicketNo(), "status", ticket.getStatus())) // 工单号和状态
                .summary("已为您创建人工服务工单 " + ticket.getTicketNo() + "，紧急程度：" + resolvedUrgency) // 摘要
                .dataSource("工单系统") // 数据来源
                .updatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) // 更新时间
                .build();
        } catch (Exception e) {
            log.error("HumanTicketTool error: {}", e.getMessage()); // 记录错误
            return ToolResult.builder().success(false).toolName("create_human_ticket")
                .error("创建工单失败：" + e.getMessage()).build(); // 返回失败结果
        }
    }
}
