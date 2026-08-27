package com.ai.agent.client.dto;

import java.io.Serializable;
import java.util.List;

/** OpenAI Responses API 兼容的 Dubbo 输入项。 */
public class LlmFacadeResponsesInput implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private String role;
    private List<LlmFacadeResponsesContent> content;
    private String callId;
    private String name;
    private String arguments;
    private String output;

    public LlmFacadeResponsesInput() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public List<LlmFacadeResponsesContent> getContent() { return content; }
    public void setContent(List<LlmFacadeResponsesContent> content) { this.content = content; }
    public String getCallId() { return callId; }
    public void setCallId(String callId) { this.callId = callId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }
    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }
}
