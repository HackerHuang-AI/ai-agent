package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: LLM token 消耗统计，结构对齐豆包 usage 响应。
 *               各平台字段映射关系：
 *                 inputTokens          ← chat: prompt_tokens          / 多模态: input_tokens      / Anthropic: input_tokens
 *                 outputTokens         ← chat: completion_tokens       / 多模态: output_tokens     / Anthropic: output_tokens
 *                 totalTokens          ← chat: total_tokens            / 多模态: total_tokens      / Anthropic: input+output 自算
 *                 inputTokensDetails   ← chat: prompt_tokens_details   / 多模态: input_tokens_details   / 无此字段时为 null
 *                 outputTokensDetails  ← chat: completion_tokens_details / 多模态: output_tokens_details / 无此字段时为 null
 *
 *               规则：平台有此字段则填值（含 0），平台无此字段则为 null。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmUsage
 * @Author: HUANGcong
 * @Date: Created in 2026/7/10
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsage {

    /** 输入 token 数 */
    private Integer inputTokens;

    /** 输出 token 数 */
    private Integer outputTokens;

    /** 总 token 数，Anthropic 无此字段时由 inputTokens + outputTokens 自算 */
    private Integer totalTokens;

    /**
     * 输入 token 明细（如缓存命中数），平台无此字段时为 null。
     * chat 对应 prompt_tokens_details；多模态对应 input_tokens_details。
     */
    private LlmInputTokensDetails inputTokensDetails;

    /**
     * 输出 token 明细（如推理 token 数），平台无此字段时为 null。
     * chat 对应 completion_tokens_details；多模态对应 output_tokens_details。
     */
    private LlmOutputTokensDetails outputTokensDetails;
}

