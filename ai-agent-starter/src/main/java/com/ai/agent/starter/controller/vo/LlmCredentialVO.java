package com.ai.agent.starter.controller.vo;

import lombok.Data;

/**
 * @Description: LLM 平台凭证入参 VO，用于不需要消息体的接口（如模型列表查询）。
 *               apiKey / endpoint 均可选，为空时从 Nacos 平台配置中兜底。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: LlmCredentialVO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/12
 * @Version: 1.0
 */
@Data
public class LlmCredentialVO {

    /** API Key，可选；为空时从 Nacos 平台配置兜底 */
    private String apiKey;
}

