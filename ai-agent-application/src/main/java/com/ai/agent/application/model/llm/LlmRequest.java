package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * @Description: LLM 统一调用入参，屏蔽各平台差异，调用方只需构造此对象
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmRequest
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmRequest {

    /** 调用方传入的 API Key */
    private String apiKey;

    /** 调用方传入的 API 请求地址 */
    private String endpoint;

    /**
     * 模型标识，对应 llm_model.model_code，如 gpt-4.1、glm-5
     * 由此字段路由到对应的平台 Adapter
     */
    private String modelCode;

    /**
     * 对话历史，按时间顺序排列
     * 首条可为 system 角色，后续为 user/assistant 交替
     */
    private List<LlmMessage> messages;

    /**
     * 多样性参数，范围 [0.0, 2.0]，越高越随机；默认 null 由各平台使用其默认值
     */
    private Double temperature;

    /**
     * nucleus sampling 参数，范围 (0, 1]；与 temperature 二选一，同时设置时平台行为不一
     * 默认 null 由各平台使用其默认值
     */
    private Double topP;

    /**
     * 单次回复最大 token 数；null 时使用 agent 配置或平台默认值
     */
    private Integer maxTokens;

    /**
     * 是否开启流式输出；默认 false（非流式）
     */
    @Builder.Default
    private boolean stream = false;

    /**
     * 会话 ID，用于调用记录关联；可为 null（直接调用场景）
     */
    private String sessionId;

    /**
     * Agent ID，用于调用记录关联；可为 null（直接调用场景）
     */
    private Long agentId;

    /**
     * 平台私有扩展参数，会被合并到请求体最外层。
     * 用于注入各平台特有字段，避免在 Service 层硬编码平台差异。
     * 示例：
     *   Deepseek reasoner 跳过 temperature → {"skip_temperature": true}
     *   未来某平台的 thinking_budget   → {"thinking_budget": 1024}
     */
    private Map<String, Object> extraParams;
}

