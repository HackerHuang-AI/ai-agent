package com.ai.agent.starter.controller.vo.deepseek;

import lombok.Builder;
import lombok.Data;

/**
 * @Description: Deepseek 平台对话响应 VO
 *               包含 Deepseek 特有字段：reasoningContent（思维链）、cacheHitTokens（KV缓存命中）
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.deepseek
 * @ClassName: DeepseekResponse
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Data
@Builder
public class DeepseekResponse {

    /** 模型回复的文本内容 */
    private String content;

    /** 实际使用的模型标识 */
    private String modelCode;

    /** 输入 token 数 */
    private int inputTokens;

    /** 输出 token 数 */
    private int outputTokens;

    /** 结束原因：stop / length / tool_calls / content_filter */
    private String finishReason;

    /**
     * 推理模型思维链内容（仅 deepseek-reasoner 有值，其余为 null）
     */
    private String reasoningContent;

    /**
     * Deepseek 特有：KV 缓存命中 token 数，命中可降低计费
     */
    private int cacheHitTokens;
}

