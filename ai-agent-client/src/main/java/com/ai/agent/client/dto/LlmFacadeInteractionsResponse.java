package com.ai.agent.client.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class LlmFacadeInteractionsResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String model;
    private String status;
    private String created;
    private String updated;
    private List<Map<String, Object>> steps;
    private String outputText;
    private String continuationToken;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Map<String, Object> usage;

    public LlmFacadeInteractionsResponse() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCreated() { return created; }
    public void setCreated(String created) { this.created = created; }
    public String getUpdated() { return updated; }
    public void setUpdated(String updated) { this.updated = updated; }
    public List<Map<String, Object>> getSteps() { return steps; }
    public void setSteps(List<Map<String, Object>> steps) { this.steps = steps; }
    public String getOutputText() { return outputText; }
    public void setOutputText(String outputText) { this.outputText = outputText; }
    public String getContinuationToken() { return continuationToken; }
    public void setContinuationToken(String continuationToken) { this.continuationToken = continuationToken; }
    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Map<String, Object> getUsage() { return usage; }
    public void setUsage(Map<String, Object> usage) { this.usage = usage; }
}

