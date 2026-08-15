package com.ai.agent.starter.controller.vo;

import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.model.llm.LlmToolCall;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * @Description: LLM 统一对话消息 VO，所有平台共用，支持纯文本和多模态两种使用模式。
 *
 * <p>模式一：纯文本（type + value）
 * <pre>{@code
 * {"role": "user", "type": "TEXT", "value": "你好"}
 * }</pre>
 *
 * <p>模式二：多模态（contents 列表，图文混合）
 * <pre>{@code
 * {"role": "user", "contents": [
 *   {"type": "IMAGE", "value": "https://example.com/img.jpg"},
 *   {"type": "TEXT",  "value": "描述这张图片"}
 * ]}
 * }</pre>
 *
 * <p>两种模式互斥，contents 非空时优先使用 contents，忽略 type/value。
 * type/value/contents 均非必填，由 Service 层根据接口类型做具体校验。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: LlmMessageVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Data
public class LlmMessageVO {

    /** 消息角色：system / user / assistant / tool */
    @NotBlank(message = "role 不能为空")
    private String role;

    /** 内容类型：TEXT / IMAGE / FILE / VIDEO，纯文本模式使用，Service 层校验 */
    private ContentTypeEnum type;

    /** 内容值：TEXT 为文本，IMAGE 为 URL 或 base64 data URI，纯文本模式使用，Service 层校验 */
    private String value;

    /** 附加说明（可选）：IMAGE 时为图片质量 "low" / "high" / "auto"（默认 auto） */
    private String detail;

    /** 多模态内容块列表，多模态模式使用；非空时优先于 type/value，Service 层校验各块内容 */
    private List<MessageContentVO> contents;

    /**
     * assistant 消息携带的工具调用列表，role=assistant 且模型要求调用工具时传入。
     * 对应 message.tool_calls，用于多轮对话回填历史。
     */
    private List<LlmToolCall> toolCalls;

    /**
     * 工具调用结果消息的关联 ID，role=tool 时必传。
     * 需与触发该次调用的 toolCalls[i].id 一致。
     */
    private String toolCallId;
}

