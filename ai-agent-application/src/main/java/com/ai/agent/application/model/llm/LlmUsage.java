package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: LLM token 消耗统计，两种协议统一字段名（以豆包多模态 usage 为基准）。
 *               各平台字段映射关系：
 *                 inputTokens     ← chat: usage.prompt_tokens     / 多模态: usage.input_tokens      / Anthropic: usage.input_tokens
 *                 outputTokens    ← chat: usage.completion_tokens  / 多模态: usage.output_tokens     / Anthropic: usage.output_tokens
 *                 totalTokens     ← chat: usage.total_tokens       / 多模态: usage.total_tokens      / Anthropic: input+output 自算
 *                 cachedTokens    ← chat: usage.prompt_tokens_details.cached_tokens / Deepseek: usage.prompt_cache_hit_tokens
 *                 reasoningTokens ← chat: usage.completion_tokens_details.reasoning_tokens / 多模态: usage.output_tokens_details.reasoning_tokens
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

    /** KV 缓存命中 token 数，仅豆包 / Deepseek 有值，其余为 null */
    private Integer cachedTokens;

    /** 推理消耗 token 数，仅 Thinking / Reasoner 类模型有值，其余为 null */
    private Integer reasoningTokens;
}

