package com.ai.agent.application.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: LLM 模型业务对象，从 llm_model 表查出后的内存表示
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: LlmModelBO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmModelBO {

    private Long id;

    /** 所属平台编码，关联 llm_platform.code */
    private String platformCode;

    /** 模型唯一标识，如 gpt-4.1 */
    private String modelCode;

    /** 模型展示名称 */
    private String modelName;

    /** 该平台的 API 请求地址 */
    private String apiEndpoint;

    /** 上下文窗口大小，单位 token */
    private int contextWindow;

    /** 每分钟最大请求次数，0=不限 */
    private int rpmLimit;

    /** 是否支持流式输出 */
    private boolean supportStream;
}

