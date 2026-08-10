package com.ai.agent.client.dto;

import java.io.Serializable;

/**
 * 工具调用 DTO，对应 message.tool_calls[] 中的单个元素。
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>响应侧：{@link LlmFacadeResponse#getToolCalls()}，模型要求调用的工具列表</li>
 *   <li>请求侧：{@link LlmFacadeMessage#getToolCalls()}，回填 assistant 历史消息用于多轮对话</li>
 * </ul>
 */
public class LlmToolCallDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 工具调用 ID，模型生成，需在后续 tool 角色消息的 toolCallId 中原样回传 */
    private String id;

    /** 工具名称，对应 function.name */
    private String name;

    /** 工具入参，JSON 字符串形式，对应 function.arguments（未解析，由调用方自行 parse） */
    private String arguments;

    public LlmToolCallDto() {}

    public LlmToolCallDto(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
}

