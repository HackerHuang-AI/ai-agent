package com.ai.agent.application.model.llm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/** OpenAI Responses API 兼容的统一输入项。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmResponsesInput {

    private String id;
    private String status;
    private List<Map<String, Object>> summary;

    @JsonProperty("encrypted_content")
    @JsonAlias("encryptedContent")
    private String encryptedContent;

    /** message / function_call / function_call_output / reasoning */
    private String type;

    /** message 类型的角色：user / developer / system / assistant */
    private String role;

    /** message 类型的内容块列表 */
    private List<LlmResponsesContent> content;

    /** function_call 类型的工具调用 ID */
    @JsonProperty("call_id")
    @JsonAlias("callId")
    private String callId;

    /** function_call 类型的工具名称 */
    private String name;

    /** function_call 类型的工具参数，JSON 字符串 */
    private String arguments;

    /** function_call_output 类型的工具执行结果 */
    private String output;

    /** 工具执行结果的多模态内容块，由工具组装线级输出 */
    @JsonIgnore
    private List<LlmResponsesContent> outputContents;
}
