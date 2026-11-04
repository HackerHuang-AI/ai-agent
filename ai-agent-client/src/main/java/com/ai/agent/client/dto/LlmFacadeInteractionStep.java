package com.ai.agent.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** Interactions 类型化输入步骤，独立于 application 模型序列化 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmFacadeInteractionStep implements Serializable {
    /** 序列化版本标识 */
    private static final long serialVersionUID = 1L;
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
    private List<LlmFacadeContent> content;
    /** 文本工具结果，与 resultContents 互斥 */
    private String result;
    /** 多模态工具结果，与 result 互斥 */
    private List<LlmFacadeContent> resultContents;
    /** 思考回放签名 */
    private String signature;
    /** 思考摘要内容 */
    private List<LlmFacadeContent> summary;
}

