package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: 输出 token 明细，对应豆包 usage.completion_tokens_details（chat）/ usage.output_tokens_details（多模态）。
 *               平台有此字段则填值（含 0），平台无此字段则整个对象为 null。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmOutputTokensDetails
 * @Author: HUANGcong
 * @Date: Created in 2026/7/10
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmOutputTokensDetails {

    /** 推理消耗 token 数，对应 reasoning_tokens，仅 Thinking/Reasoner 类模型有值 */
    private Integer reasoningTokens;
}

