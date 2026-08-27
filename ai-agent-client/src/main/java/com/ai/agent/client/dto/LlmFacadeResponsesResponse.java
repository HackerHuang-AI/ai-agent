package com.ai.agent.client.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** LlmFacade Dubbo Responses API 响应 DTO。 */
public class LlmFacadeResponsesResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String requestId;
    private String modelCode;
    private String status;
    private Integer maxOutputTokens;
    private int inputTokens;
    private int outputTokens;
    private List<Map<String, Object>> output;

    public LlmFacadeResponsesResponse() {}

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public List<Map<String, Object>> getOutput() { return output; }
    public void setOutput(List<Map<String, Object>> output) { this.output = output; }
}
