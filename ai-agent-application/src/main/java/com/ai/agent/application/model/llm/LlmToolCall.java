package com.ai.agent.application.model.llm;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具调用请求，对应 OpenAI/豆包协议 message.tool_calls[] 中的单个元素。
 *               出现在两个场景：
 *               1. 响应侧：{@link LlmChoice#getToolCalls()}，模型要求调用的工具列表
 *               2. 请求侧：{@link LlmMessage#getToolCalls()}，回填 assistant 历史消息用于多轮对话
 * @author HUANGcong
 * @since 2026-08-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmToolCall {

    /** 工具调用 ID，模型生成，需在后续 tool 角色消息的 toolCallId 中原样回传 */
    private String id;

    /** 工具名称，对应 function.name */
    private String name;

    /** 工具入参，JSON 字符串形式，对应 function.arguments（未解析，由调用方自行 parse） */
    private String arguments;

    /** 原始 tool_calls.extra_content，包含 google.thought_signature。 */
    @JsonAlias("extra_content")
    private Map<String, Object> extraContent;
}

