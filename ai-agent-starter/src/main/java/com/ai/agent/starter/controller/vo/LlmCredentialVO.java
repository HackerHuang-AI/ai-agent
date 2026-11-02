package com.ai.agent.starter.controller.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * LLM 平台凭证入参 VO，用于不需要消息体的接口（如模型列表查询）。
 *               apiKey / endpoint 均可选，为空时从 Nacos 平台配置中兜底。
 *
 * @author HUANGcong
 * @since 2026-07-12
 */
@Data
public class LlmCredentialVO {

    /** 平台编码；统一模型列表接口必传，使用 PascalCase */
    private String platform;

    /** 页码，从 1 开始，默认 1 */
    @Min(value = 1, message = "2001010")
    private Integer pageNo = 1;

    /** 每页数量，默认 10，最大 100 */
    @Min(value = 1, message = "2001011")
    @Max(value = 100, message = "2001011")
    private Integer pageSize = 10;

    /** API Key，可选；为空时从 Nacos 平台配置兜底 */
    private String apiKey;
}

