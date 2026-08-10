package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Description: Chat Completions 协议单个候选回复（对应 choices[i]）
 *               同一次请求如果 n>1，则有多个候选，全部透传不截断。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmChoice
 * @Author: HUANGcong
 * @Date: Created in 2026/7/10
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmChoice {

    /** 模型正文回复，对应 choices[i].message.content。finishReason=tool_calls 时可能为空字符串 */
    private String content;

    /**
     * 模型思考过程，对应 choices[i].message.reasoning_content。
     * 仅 Deepseek-reasoner / 豆包 Thinking 模型有值，其他平台为 null。
     */
    private String reasoningContent;

    /**
     * 结束原因，统一值域：stop / length / content_filter / tool_calls。
     * 对应 choices[i].finish_reason（Anthropic 的 end_turn 映射为 stop）。
     */
    private String finishReason;

    /**
     * 模型要求调用的工具列表，对应 choices[i].message.tool_calls。
     * 仅 finishReason=tool_calls 时有值，其余场景为 null。
     */
    private List<LlmToolCall> toolCalls;
}

