package com.ai.agent.application.model.llm;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @Description: LLM 平台模型信息，统一封装各平台 GET /models 返回的模型元数据。
 *               各平台字段丰富程度不同，不支持的字段返回 null。
 *
 * <p>字段支持情况：
 * <ul>
 *   <li>豆包：全部字段</li>
 *   <li>Moonshot / DeepSeek / Qwen（兼容模式）：仅 id / ownedBy / created，其余 null</li>
 * </ul>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmModelInfo
 * @Author: HUANGcong
 * @Date: Created in 2026/7/12
 * @Version: 1.0
 */
@Data
@Builder
public class LlmModelInfo {

    /** 模型唯一标识，如 doubao-1-5-pro-32k、moonshot-v1-8k */
    private String id;

    /** 模型展示名称，如 doubao-1-5-pro（豆包有，其他平台通常与 id 相同或 null） */
    private String name;

    /** 所属方，如 doubao、moonshot、deepseek */
    private String ownedBy;

    /** 模型创建时间戳（Unix 秒） */
    private Long created;

    /**
     * 模型状态。
     * 豆包：Active / Shutdown
     * 其他平台：null（无此字段，视为可用）
     */
    private String status;

    /**
     * 模型领域/类型。
     * 豆包：LLM / VLM / Embedding
     * 其他平台：null
     */
    private String domain;

    /** 模型版本号，如 250328（豆包有，其他 null） */
    private String version;

    /** 任务类型列表，如 ["TextGeneration"]、["VisualQuestionAnswering"]（豆包有，其他 null） */
    private List<String> taskTypes;

    // ==================== 模态 ====================

    /** 支持的输入模态，如 ["text"]、["text","image","video"]（豆包有，其他 null） */
    private List<String> inputModalities;

    /** 支持的输出模态，如 ["text"]（豆包有，其他 null） */
    private List<String> outputModalities;

    // ==================== Token 限制 ====================

    /** 上下文窗口大小（token 数），如 32768（豆包有，其他 null） */
    private Integer contextWindow;

    /** 最大输入 token 数（豆包有，其他 null） */
    private Integer maxInputTokens;

    /** 最大输出 token 数，如 16384（豆包有，其他 null） */
    private Integer maxOutputTokens;

    // ==================== 能力特性 ====================

    /** 是否支持 Function Call / Tool Use（豆包有，其他 null） */
    private Boolean supportFunctionCalling;

    /** 是否支持批量推理（batch_chat/batch_job）（豆包有，其他 null） */
    private Boolean supportBatch;

    /** 是否支持结构化输出 json_object（豆包有，其他 null） */
    private Boolean supportJsonObject;

    /** 是否支持结构化输出 json_schema（豆包有，其他 null） */
    private Boolean supportJsonSchema;

    /**
     * 平台原始扩展字段，保留平台返回的未映射字段，避免信息丢失。
     * 各平台未来新增字段可通过此字段透传。
     */
    private Map<String, Object> extra;
}

