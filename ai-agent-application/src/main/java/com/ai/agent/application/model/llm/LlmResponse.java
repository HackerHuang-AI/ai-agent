package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * @Description: LLM 统一调用返回，调用方收到此对象，无需感知底层平台差异。
 *               Chat 场景填 choices + usage，多模态场景填 output + status + maxOutputTokens + usage。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmResponse
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmResponse {

    // ==================== 通用标识 ====================

    /** 平台请求 ID，对应 root.id，用于问题排查 */
    private String requestId;

    /** 实际使用的模型标识，关联 llm_model.model_code */
    private String modelCode;

    /**
     * 平台返回的时间戳（Unix 秒）。
     * Chat Completions → root.created；Responses API → root.created_at；
     * Anthropic 无此字段时，由调用方记录请求发起时间填入。
     */
    private Long createdAt;

    // ==================== Chat 响应（choices 结构）====================

    /**
     * Chat Completions 协议候选回复列表，对应 root.choices[]。
     * 平台返回几个就透传几个（由请求参数 n 控制，默认 n=1 只有一个元素）。
     * 多模态调用时为 null。
     */
    private List<LlmChoice> choices;

    // ==================== 多模态响应（output 结构）====================

    /**
     * Responses API 整体状态，如 "completed"。
     * 普通 Chat 调用时为 null。
     */
    private String status;

    /**
     * 本次响应允许的最大输出 token 数，对应 root.max_output_tokens。
     * 普通 Chat 调用时为 null。
     */
    private Integer maxOutputTokens;

    /**
     * Responses API 输出节点列表，对应 root.output[]。
     * 每个节点 type 为 reasoning（思考过程）或 message（正文回复）。
     * 普通 Chat 调用时为 null。
     */
    private List<LlmOutputItem> output;

    // ==================== Token 消耗 ====================

    /** token 消耗统计，chat 和多模态均填充，字段名统一以豆包多模态 usage 为基准 */
    private LlmUsage usage;

    // ==================== 扩展 ====================

    /**
     * 平台私有响应字段，各平台按需填充，调用方按需取用，不存在时为 null。
     */
    private Map<String, Object> extraData;
}

