package com.ai.agent.starter.vo;

import com.ai.agent.application.model.llm.LlmResponsesInput;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 统一 Responses API 请求。
 * input 使用类型字段树，由各平台独立校验。
 */
@Data
public class LlmResponsesRequestVO {

    /** 平台编码，使用 PascalCase */
    private String platform;

    /** API Key，可为空并由平台配置兜底 */
    private String apiKey;

    /** Responses API 地址，可为空并由平台配置兜底 */
    private String endpoint;

    /** Responses API 的模型标识 */
    private String model;

    /** Responses API 类型化输入项列表 */
    @NotEmpty(message = "input 不能为空")
    private List<LlmResponsesInput> input;

    /** Responses API 的系统指令 */
    private String instructions;

    /** 采样温度 */
    private Double temperature;

    /** 核采样参数 */
    private Double topP;

    /** 最大输出 token 数 */
    private Integer maxOutputTokens;

    /** Responses API 工具定义 */
    private List<Map<String, Object>> tools;

    /** 工具选择策略 */
    private String toolChoice;

    /** 平台私有的 Responses API 顶层参数 */
    private Map<String, Object> extraParams;
}
