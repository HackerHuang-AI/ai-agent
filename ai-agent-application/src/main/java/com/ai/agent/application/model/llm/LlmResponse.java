package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

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
     * 平台私有响应字段，各平台按需填充，调用方按需取用，不存在时为 null。
     * 示例：
     *   Deepseek reasoner → {"reasoning_content": "...", "cache_hit_tokens": 128}
     *   未来平台的私有字段同理
     */
    private Map<String, Object> extraData;
}

