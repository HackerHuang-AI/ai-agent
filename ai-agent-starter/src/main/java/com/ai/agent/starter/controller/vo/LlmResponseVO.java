package com.ai.agent.starter.controller.vo;

import com.ai.agent.application.model.llm.LlmChoice;
import com.ai.agent.application.model.llm.LlmOutputItem;
import com.ai.agent.application.model.llm.LlmUsage;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @Description: LLM 统一调用响应 VO，所有平台共用。
 *               Chat 场景填 choices + usage；多模态场景填 output + status + maxOutputTokens + usage。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: LlmResponseVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Data
@Builder
public class LlmResponseVO {

    // ==================== 通用标识 ====================

    /** 平台请求 ID，用于问题排查 */
    private String requestId;

    /** 实际使用的模型标识 */
    private String modelCode;

    /** 平台返回的时间戳（Unix 秒）；Anthropic 无此字段时由请求发起时间填入 */
    private Long createdAt;

    // ==================== Chat 响应（choices 结构）====================

    /** Chat Completions 候选回复列表，平台返回几个透传几个；多模态调用时为 null */
    private List<LlmChoice> choices;

    // ==================== 多模态响应（output 结构）====================

    /** Responses API 整体状态，如 "completed"；普通 Chat 时为 null */
    private String status;

    /** 最大输出 token 数，Responses API 专有；普通 Chat 时为 null */
    private Integer maxOutputTokens;

    /** Responses API 输出节点列表（reasoning / message）；普通 Chat 时为 null */
    private List<LlmOutputItem> output;

    // ==================== Token 消耗 ====================

    /** token 消耗统计，chat 和多模态均填充 */
    private LlmUsage usage;

    // ==================== 扩展 ====================

    /** 平台私有响应字段，不存在时为 null */
    private Map<String, Object> extraData;
}

