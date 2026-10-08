package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 输入 token 明细，对应豆包 usage.prompt_tokens_details（chat）/ usage.input_tokens_details（多模态）。
 *               平台有此字段则填值（含 0），平台无此字段则整个对象为 null。
 * @author HUANGcong
 * @since 2026-07-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmInputTokensDetails {

    /** KV 缓存命中 token 数，对应 cached_tokens */
    private Integer cachedTokens;
}

