package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: 工具调用请求，对应 OpenAI/豆包协议 message.tool_calls[] 中的单个元素。
 *               出现在两个场景：
 *               1. 响应侧：{@link LlmChoice#getToolCalls()}，模型要求调用的工具列表
 *               2. 请求侧：{@link LlmMessage#getToolCalls()}，回填 assistant 历史消息用于多轮对话
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmToolCall
 * @Author: HUANGcong
 * @Date: Created in 2026/8/10
 * @Version: 1.0
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
}

