package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Responses API 统一请求。
 * input 保持厂商 Responses API 的原始结构，由对应平台适配器直接透传。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponsesRequest {

    /** 调用方传入的 API Key */
    private String apiKey;

    /** 调用方传入的 Responses API 地址 */
    private String endpoint;

    /** Responses API 的模型标识 */
    private String model;

    /** Responses API 的 input 字段 */
    private Object input;

    /** Responses API 的系统指令 */
    private String instructions;

    /** 采样温度；null 时使用平台默认值 */
    private Double temperature;

    /** 核采样参数；null 时使用平台默认值 */
    private Double topP;

    /** 最大输出 token 数；null 时使用平台默认值 */
    private Integer maxOutputTokens;

    /**
     * Responses API 工具定义，对应顶层 tools。
     * 格式使用 Responses 协议，例如 {@code {"type":"function","name":"weather","parameters":{...}}}。
     */
    private List<Map<String, Object>> tools;

    /** 工具选择策略：auto / none / required / 指定工具名 */
    private String toolChoice;

    /** 平台私有的 Responses API 顶层参数；不得覆盖公共字段。 */
    private Map<String, Object> extraParams;
}
