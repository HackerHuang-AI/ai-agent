package com.ai.agent.client.dto;

import java.io.Serializable;
import java.util.Map;

/**
 * LlmFacade Dubbo 接口响应 DTO
 */
public class LlmFacadeResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模型回复的文本内容 */
    private String content;

    /** 实际使用的模型标识 */
    private String modelCode;

    /** 输入 token 数 */
    private int inputTokens;

    /** 输出 token 数 */
    private int outputTokens;

    /**
     * 结束原因：stop / length / tool_calls / content_filter
     */
    private String finishReason;

    /**
     * 平台私有响应字段（如 Deepseek 的 reasoning_content），按需取用
     */
    private Map<String, Object> extraData;

    public LlmFacadeResponse() {}

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
    public Map<String, Object> getExtraData() { return extraData; }
    public void setExtraData(Map<String, Object> extraData) { this.extraData = extraData; }
}

