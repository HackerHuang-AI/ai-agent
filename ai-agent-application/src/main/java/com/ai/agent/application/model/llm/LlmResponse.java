package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: LLM 统一调用返回，调用方收到此对象，无需感知底层平台差异
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmResponse
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    /** 模型回复的文本内容 */
    private String content;

    /** 实际使用的模型标识，关联 llm_model.model_code */
    private String modelCode;

    /** 本次消耗的输入 token 数 */
    private int inputTokens;

    /** 本次消耗的输出 token 数 */
    private int outputTokens;

    /**
     * 模型结束原因
     * stop：正常结束；length：达到 maxTokens；tool_calls：触发工具调用；content_filter：内容过滤
     */
    private String finishReason;

    /**
     * 推理模型的思维链内容（仅 Deepseek-Reasoner 等推理模型返回，其余为 null）
     */
    private String reasoningContent;

    /**
     * KV 缓存命中 token 数（仅 Deepseek 返回，命中可降低计费；其余平台为 0）
     */
    private int cacheHitTokens;
}

