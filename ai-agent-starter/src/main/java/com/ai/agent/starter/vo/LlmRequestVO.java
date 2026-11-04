package com.ai.agent.starter.vo;

import com.ai.agent.application.model.llm.LlmInteractionStep;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.groups.Default;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * LLM 统一调用入参 VO，所有平台共用。
 *               公共参数直接填写，平台私有参数放入 extraParams。
 *               凭证字段（apiKey / endpoint / modelCode）可选，为空时从 Nacos 兜底（仅豆包支持）。
 *               豆包：modelCode 填 endpoint_id，如 ep-20240101-xxxxx
 *               Deepseek：modelCode 填模型名，如 deepseek-chat / deepseek-reasoner
 * @author HUANGcong
 * @since 2026-06-28
 */
@Data
public class LlmRequestVO {

    public interface Chat extends Default {}

    /** 平台编码，统一接口必传，平台专属接口不需要传。支持：Doubao / OpenAI / Deepseek / Anthropic / Glm / Qwen / Moonshot / MiniMax / Gemini / Ollama / Qianfan / TokenHub / Mimo */
    private String platform;

    /** API Key，可选（支持 Nacos 兜底的平台可不传） */
    private String apiKey;

    /** API 请求地址，可选（支持 Nacos 兜底的平台可不传） */
    private String endpoint;

    /**
     * 各平台 Chat 模型标识，可选（支持 Nacos 兜底的平台可不传）；Gemini Interactions 使用 model。
     * 豆包填 endpoint_id（如 ep-20240101-xxxxx），Deepseek 填模型名（如 deepseek-chat）
     */
    private String modelCode;

    /** 各平台 Chat 对话消息，首条可为 system；Gemini Interactions 仅接受 user/assistant 并映射为输入步骤。 */
    @Valid
    @NotEmpty(message = "messages 不能为空", groups = Chat.class)
    private List<LlmMessageVO> messages;

    /** Gemini Interactions 专用模型名称；Chat 使用 modelCode，两者不互相兜底。 */
    private String model;

    /** Gemini Interactions 专用系统指令，对应 system_instruction；Chat 使用 system 消息。 */
    private String instructions;

    /** Gemini Interactions 专用 generation_config，保留协议原始参数。 */
    private Map<String, Object> generationConfig;

    /** Gemini Interactions 专用上一轮交互标识，对应 previous_interaction_id。 */
    private String previousInteractionId;

    /** Gemini Interactions 专用续写令牌，对应 continuation_token。 */
    private String continuationToken;

    /** Gemini Interactions 专用：是否存储交互，对应 store。 */
    private Boolean store;

    /** Gemini Interactions 专用：是否后台执行，对应 background。 */
    private Boolean background;

    /** Gemini Interactions 专用最大输出 token 数；Chat 使用 maxTokens，两者不互相兜底。 */
    private Integer maxOutputTokens;

    /** Gemini Interactions 专用工具或思考回放步骤；与 messages 的互斥规则由 Gemini 实现校验。 */
    @Valid
    private List<LlmInteractionStep> interactionSteps;

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
     * 可用工具定义列表，格式与 OpenAI function calling 一致：
     * {@code [{"type":"function","function":{"name":..,"description":..,"parameters":{JSON Schema}}}]}
     * Gemini Interactions 使用原生平铺 function 定义：{"type":"function","name":...,"parameters":...}。
     * 为空/null 时不注入 tools 参数，即普通对话模式。
     */
    private List<Map<String, Object>> tools;

    /**
     * Chat 工具选择策略，对应 tool_choice；Gemini Interactions 使用 generationConfig.tool_choice：
     * "auto"（默认）/ "none"（禁止调用）/ 指定工具名（强制调用该工具）
     * null 时不传，由平台使用默认行为。
     */
    private String toolChoice;

    /**
     * 平台私有扩展参数，合并到请求体最外层。
     * 示例：Deepseek：{"presence_penalty": 0.3}
     */
    private Map<String, Object> extraParams;
}

