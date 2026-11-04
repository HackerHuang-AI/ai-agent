package com.ai.agent.starter.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * LLM 平台凭证入参 VO，用于不需要消息体的接口（如模型列表查询）。
 *               apiKey / endpoint 均可选，为空时从 Nacos 平台配置中兜底。
 *
 * @author HUANGcong
 * @since 2026-07-12
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LlmCredentialVO extends BasePageInVO {

    /** 平台编码；统一模型列表接口必传，使用 PascalCase */
    private String platform;

    /** API Key，可选；为空时从 Nacos 平台配置兜底 */
    private String apiKey;
}

