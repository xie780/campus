package com.simon.campus.service.agent;

import com.simon.campus.common.BizException;
import com.simon.campus.service.ingest.VisionTextExtractor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 视觉问答服务：将图片解析为文本并与用户问题组合为增强查询
 */
@Service
@RequiredArgsConstructor
public class VisionQuestionService {

    private final VisionTextExtractor visionTextExtractor; // 视觉文本提取器

    /**
     * 构建图片提问的增强查询：提取图片文本并与用户问题组合
     */
    public String buildImageQuestion(String question, byte[] imageBytes, String mimeType) throws Exception {
        if (!visionTextExtractor.isAvailable()) { // 检查视觉服务是否可用
            throw new BizException("图片理解服务未配置，请检查 DashScope API Key");
        }
        String imageText = visionTextExtractor.extractImageAsMarkdown(imageBytes, mimeType); // 提取图片文本
        if (imageText == null || imageText.isBlank()) { // 检查提取结果
            throw new BizException("未能识别图片内容，请换一张更清晰的图片");
        }
        String normalizedQuestion = question == null || question.isBlank() // 处理空问题
            ? "请分析这张图片，并结合校园事务场景回答。"
            : question.strip();
        return """ 
            用户上传了一张图片，请先基于图片解析内容理解图片，再回答用户问题。

            【图片解析内容】
            %s

            【用户问题】
            %s
            """.formatted(imageText.strip(), normalizedQuestion);
            // 组合图片文本和用户问题为增强查询
    }
}
