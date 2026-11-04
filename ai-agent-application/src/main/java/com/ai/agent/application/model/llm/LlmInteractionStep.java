package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** Gemini 原生 Interactions 专属类型化输入步骤，用于消息、工具和思考回放；当前全部字段仅用于该协议，Chat 不使用。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInteractionStep {
    /** 步骤类型：user_input / model_output / function_call / function_result / thought */
    private String type;
    /** 步骤标识，工具调用时为调用标识 */
    private String id;
    /** 工具结果关联的调用标识 */
    private String callId;
    /** 工具或步骤名称 */
    private String name;
    /** 工具调用参数 */
    private Map<String, Object> arguments;
    /** 消息或思考内容 */
    private List<MessageContent> content;
    /** 文本工具结果，与 resultContents 互斥 */
    private String result;
    /** 多模态工具结果，与 result 互斥 */
    private List<MessageContent> resultContents;
    /** 思考回放签名 */
    private String signature;
    /** 思考摘要内容 */
    private List<MessageContent> summary;
}

