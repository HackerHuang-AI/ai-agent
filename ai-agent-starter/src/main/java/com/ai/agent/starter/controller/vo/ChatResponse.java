package com.ai.agent.starter.controller.vo;

import lombok.Builder;
import lombok.Data;

/**
 * @Description: LLM 对话接口响应 VO
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: ChatResponse
 * @Author: HUANGcong
 * @Date: Created in 2026/6/2
 * @Version: 1.0
 */
@Data
@Builder
public class ChatResponse {

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
     * 推理模型思维链（仅 Deepseek-Reasoner 等推理模型有值，其余为 null）
     */
    private String reasoningContent;
}

