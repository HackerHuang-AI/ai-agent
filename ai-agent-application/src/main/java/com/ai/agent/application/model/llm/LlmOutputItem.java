package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Description: Responses API 输出节点，对应豆包 Responses API output[] 数组中的每个元素。
 *               type 取值：
 *                 reasoning - 模型思考过程，内容在 summary 字段
 *                 message   - 模型对用户的回复，内容在 content 字段
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmOutputItem
 * @Author: HUANGcong
 * @Date: Created in 2026/7/10
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmOutputItem {

    /** 节点 ID */
    private String id;

    /** 节点类型：reasoning / message */
    private String type;

    /** 节点状态：completed 等 */
    private String status;

    /** 工具调用 ID，type=function_call 时有值，需作为 function_call_output 的 call_id 回传。 */
    private String callId;

    /** 工具名称，type=function_call 时有值。 */
    private String name;

    /** 工具参数，type=function_call 时有值，为 JSON 字符串。 */
    private String arguments;

    /**
     * 思考摘要块列表，type=reasoning 时有值，对应 output[i].summary[]。
     * type=message 时为 null。
     */
    private List<LlmContentBlock> summary;

    /**
     * 角色，type=message 时有值（固定为 "assistant"），其余为 null。
     */
    private String role;

    /**
     * 输出内容块列表，type=message 时有值，对应 output[i].content[]。
     * type=reasoning 时为 null。
     */
    private List<LlmContentBlock> content;
}

