package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: 输入 token 明细，对应豆包 usage.prompt_tokens_details（chat）/ usage.input_tokens_details（多模态）。
 *               平台有此字段则填值（含 0），平台无此字段则整个对象为 null。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmInputTokensDetails
 * @Author: HUANGcong
 * @Date: Created in 2026/7/10
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInputTokensDetails {

    /** KV 缓存命中 token 数，对应 cached_tokens */
    private Integer cachedTokens;
}

