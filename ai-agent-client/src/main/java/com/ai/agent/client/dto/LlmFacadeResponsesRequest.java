package com.ai.agent.client.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** LlmFacade Dubbo Responses API 请求 DTO。 */
public class LlmFacadeResponsesRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String platform;
    private String model;
    private String apiKey;
    private String endpoint;
    private List<LlmFacadeResponsesInput> input;
    private String instructions;
    private Double temperature;
    private Double topP;
    private Integer maxOutputTokens;
    private List<Map<String, Object>> tools;
    private String toolChoice;
    private Map<String, Object> extraParams;

    public LlmFacadeResponsesRequest() {}

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public List<LlmFacadeResponsesInput> getInput() { return input; }
    public void setInput(List<LlmFacadeResponsesInput> input) { this.input = input; }
    public String getInstructions() { return instructions; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Integer maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public List<Map<String, Object>> getTools() { return tools; }
    public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }
    public String getToolChoice() { return toolChoice; }
    public void setToolChoice(String toolChoice) { this.toolChoice = toolChoice; }
    public Map<String, Object> getExtraParams() { return extraParams; }
    public void setExtraParams(Map<String, Object> extraParams) { this.extraParams = extraParams; }
}
