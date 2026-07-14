package com.ai.agent.starter.controller.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @Description: LLM 统一调用入参 VO，所有平台共用。
 *               公共参数直接填写，平台私有参数放入 extraParams。
 *               凭证字段（apiKey / endpoint / modelCode）可选，为空时从 Nacos 兜底（仅豆包支持）。
 *               豆包：modelCode 填 endpoint_id，如 ep-20240101-xxxxx
 *               Deepseek：modelCode 填模型名，如 deepseek-chat / deepseek-reasoner
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: LlmRequestVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Data
public class LlmRequestVO {

    /** API Key，可选（支持 Nacos 兜底的平台可不传） */
    private String apiKey;

    /** API 请求地址，可选（支持 Nacos 兜底的平台可不传） */
    private String endpoint;

    /**
     * 模型标识，可选（支持 Nacos 兜底的平台可不传）
     * 豆包填 endpoint_id（如 ep-20240101-xxxxx），Deepseek 填模型名（如 deepseek-chat）
     */
    private String modelCode;

    /** 对话消息列表，按时间顺序，首条可为 system 角色 */
    @NotEmpty(message = "messages 不能为空")
    @Valid
    private List<LlmMessageVO> messages;

    /** 温度参数，范围因平台而异，null 时使用平台默认值 */
    private Double temperature;

    /** nucleus sampling 参数，范围 (0, 1]，与 temperature 二选一，null 时使用平台默认值 */
    private Double topP;

    /**
     * Top-K 采样：每步只从概率最高的 K 个词中选择。
     * 范围 [1, ∞)，null 时使用平台默认。OpenAI / Moonshot / Deepseek 不支持。
     */
    private Integer topK;

    /**
     * 频率惩罚：降低重复词出现概率，范围 [-2.0, 2.0]。
     * null 时不传。Anthropic 不支持，Deepseek 已 deprecated（无效果），Moonshot 文档无此参数。
     */
    private Double frequencyPenalty;

    /** 单次回复最大 token 数，null 时使用平台默认值 */
    private Integer maxTokens;

    /**
     * 平台私有扩展参数，合并到请求体最外层。
     * 示例：Deepseek：{"presence_penalty": 0.3}
     */
    private Map<String, Object> extraParams;
}

