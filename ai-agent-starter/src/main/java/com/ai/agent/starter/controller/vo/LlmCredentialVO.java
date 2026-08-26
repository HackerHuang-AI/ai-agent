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

    /** 平台标识；统一模型列表接口必传 */
    private String platform;

    /** 页码，从 1 开始，默认 1 */
    private Integer pageNo;

    /** 每页数量，默认 20，最大 20 */
    private Integer pageSize;

    /** API Key，可选；为空时从 Nacos 平台配置兜底 */
    private String apiKey;
}

