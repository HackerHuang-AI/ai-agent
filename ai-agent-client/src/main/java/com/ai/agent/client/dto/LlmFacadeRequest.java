package com.ai.agent.client.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * LlmFacade Dubbo 接口请求 DTO
 * <p>Consumer 侧填写此对象，ai-agent Provider 端路由到对应平台 Service 执行</p>
 *
 * <h3>平台路由规则（platform 字段）</h3>
 * <pre>
 *   doubao      → 豆包（火山方舟）
 *   openai      → OpenAI
 *   deepseek    → Deepseek
 *   anthropic   → Anthropic（Claude）
 *   zhipu       → 智谱 GLM
 *   qwen        → 阿里灵积（通义千问）
 *   moonshot    → Moonshot（Kimi）
 *   minimax     → Minimax
 *   gemini      → Google Gemini
 *   ollama      → Ollama（本地部署）
 *   qianfan     → 百度千帆
 *   tokenhub    → 腾讯 TokenHub
 * </pre>
 */
public class LlmFacadeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 平台标识，用于路由到对应 Service。
     * 不区分大小写，内部统一转小写处理。
     */
    private String platform;

    /**
     * 模型标识（如 doubao-pro-32k、gpt-4o），不传时使用平台默认模型。
     */
    private String modelCode;

    /** API Key（不传时从 Nacos 平台配置中读取） */
    private String apiKey;

    /** API Endpoint（不传时从 Nacos 平台配置中读取） */
    private String endpoint;

    /** 对话消息列表（按时间顺序，首条可为 system） */
    private List<LlmFacadeMessage> messages;

    /** 采样温度 [0.0, 2.0]，null 时使用平台默认值 */
    private Double temperature;

    /** nucleus sampling，null 时使用平台默认值 */
    private Double topP;

    /**
     * Top-K 采样，范围 [1, ∞)，null 时不传。OpenAI / Moonshot / Deepseek 不支持会被平台忽略。
     */
    private Integer topK;

    /**
     * 频率惩罚，范围 [-2.0, 2.0]，降低重复率。null 时不传。
     * Anthropic 不支持，Deeepseek已 deprecated，Moonshot 文档无此参数。
     */
    private Double frequencyPenalty;

    /** 最大输出 token 数，null 时使用平台默认值 */
    private Integer maxTokens;

    /**
     * 平台私有扩展参数，透传合并到请求体。
     * 示例：Deepseek reasoner → {"skip_temperature": true}
     */
    private Map<String, Object> extraParams;

    public LlmFacadeRequest() {}

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public List<LlmFacadeMessage> getMessages() { return messages; }
    public void setMessages(List<LlmFacadeMessage> messages) { this.messages = messages; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(Double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public Map<String, Object> getExtraParams() { return extraParams; }
    public void setExtraParams(Map<String, Object> extraParams) { this.extraParams = extraParams; }
}

