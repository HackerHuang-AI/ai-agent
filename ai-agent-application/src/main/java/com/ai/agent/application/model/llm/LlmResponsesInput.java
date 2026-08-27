package com.ai.agent.application.model.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** OpenAI Responses API 兼容的统一输入项。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponsesInput {

    /** message / function_call / function_call_output */
    private String type;

    /** message 类型的角色：user / developer / system / assistant */
    private String role;

    /** message 类型的内容块列表 */
    private List<LlmResponsesContent> content;

    /** function_call 类型的工具调用 ID */
    @JsonProperty("call_id")
    private String callId;

    /** function_call 类型的工具名称 */
    private String name;

    /** function_call 类型的工具参数，JSON 字符串 */
    private String arguments;

    /** function_call_output 类型的工具执行结果 */
    private String output;
}
