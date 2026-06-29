package com.ai.agent.starter.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * @Description: LLM 统一调用响应 VO，所有平台共用。
 *               公共字段直接返回，平台私有响应字段放入 extraData。
 *               示例：Deepseek reasoner 的思维链内容在 extraData.reasoning_content
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: LlmResponseVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Data
@Builder
public class LlmResponseVO {

    /** 模型回复的文本内容 */
    private String content;

    /** 实际使用的模型标识（豆包返回 endpoint_id，Deepseek 返回模型名） */
    private String modelCode;

    /** 本次消耗的输入 token 数 */
    private int inputTokens;

    /** 本次消耗的输出 token 数 */
    private int outputTokens;

    /** 结束原因：stop / length / tool_calls / content_filter */
    private String finishReason;

    /**
     * 平台私有响应字段，不存在时为 null。
     * 示例：Deepseek reasoner → {"reasoning_content": "...", "cache_hit_tokens": 128}
     */
    private Map<String, Object> extraData;
}

