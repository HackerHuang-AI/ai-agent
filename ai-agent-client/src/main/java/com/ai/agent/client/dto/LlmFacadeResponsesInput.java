package com.ai.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API 兼容的 Dubbo 输入项。
 *
 * @author HUANGcong
 * @since 2026-05-29
 */
public class LlmFacadeResponsesInput implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String status;
    private List<Map<String, Object>> summary;
    @JsonAlias("encrypted_content")
    private String encryptedContent;
    private String type;
    private String role;
    private List<LlmFacadeResponsesContent> content;
    @JsonAlias("call_id")
    private String callId;
    private String name;
    private String arguments;
    private String output;

    /** 工具执行结果的多模态内容块，由工具组装线级输出 */
    @JsonIgnore
    private List<LlmFacadeResponsesContent> outputContents;

    public LlmFacadeResponsesInput() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<Map<String, Object>> getSummary() { return summary; }
    public void setSummary(List<Map<String, Object>> summary) { this.summary = summary; }
    public String getEncryptedContent() { return encryptedContent; }
    public void setEncryptedContent(String encryptedContent) { this.encryptedContent = encryptedContent; }
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
    /** 获取工具执行结果的多模态内容块 */
    public List<LlmFacadeResponsesContent> getOutputContents() { return outputContents; }
    /** 设置工具执行结果的多模态内容块 */
    public void setOutputContents(List<LlmFacadeResponsesContent> outputContents) { this.outputContents = outputContents; }
}
