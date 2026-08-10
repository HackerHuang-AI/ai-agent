package com.ai.agent.application.model.llm;

import com.ai.agent.application.enums.ContentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Description: LLM 对话消息，支持纯文本和多模态内容（图片/文件/视频）
 *               role 枚举：system / user / assistant / tool
 *
 *               纯文本场景（兼容旧用法）：
 *                   LlmMessage.ofText("user", "你好")
 *
 *               多模态场景：
 *                   LlmMessage.ofMultiModal("user", List.of(
 *                       MessageContent.ofText("描述这张图片"),
 *                       MessageContent.ofImage("https://example.com/img.jpg")
 *                   ))
 *
 *               工具调用场景（回填多轮对话历史）：
 *                   assistant 消息携带 toolCalls：LlmMessage.ofToolCalls(toolCalls)
 *                   tool 消息回传执行结果：LlmMessage.ofToolResult(toolCallId, resultText)
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmMessage
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmMessage {

    /** 消息角色：system / user / assistant / tool */
    private String role;

    /**
     * 多模态内容列表。
     * 纯文本消息：列表中只有一个 ContentType.TEXT 元素。
     * 多模态消息：文本 + 图片/文件/视频 的组合。
     * assistant 携带 toolCalls 且无正文回复时可为空。
     */
    private List<MessageContent> contents;

    /**
     * assistant 消息携带的工具调用请求列表，对应 message.tool_calls。
     * 仅 role=assistant 且模型要求调用工具时有值，其余场景为 null。
     */
    private List<LlmToolCall> toolCalls;

    /**
     * 本条消息对应的工具调用 ID，对应 message.tool_call_id。
     * 仅 role=tool 时有值，需与触发该次调用的 {@link LlmToolCall#getId()} 一致。
     */
    private String toolCallId;

    /** 便捷工厂：纯文本消息 */
    public static LlmMessage ofText(String role, String text) {
        return LlmMessage.builder()
                .role(role)
                .contents(List.of(MessageContent.ofText(text)))
                .build();
    }

    /** 便捷工厂：多模态消息 */
    public static LlmMessage ofMultiModal(String role, List<MessageContent> contents) {
        return LlmMessage.builder()
                .role(role)
                .contents(contents)
                .build();
    }

    /** 便捷工厂：assistant 携带工具调用请求的消息（回填多轮对话历史） */
    public static LlmMessage ofToolCalls(List<LlmToolCall> toolCalls) {
        return ofToolCalls(toolCalls, null);
    }

    /**
     * 便捷工厂：assistant 携带工具调用请求的消息，同时保留模型输出的正文文本
     * （如"好的，我来查一下"），协议上 content 与 tool_calls 可同时存在。
     */
    public static LlmMessage ofToolCalls(List<LlmToolCall> toolCalls, String content) {
        return LlmMessage.builder()
                .role("assistant")
                .toolCalls(toolCalls)
                .contents(content == null ? null : List.of(MessageContent.ofText(content)))
                .build();
    }

    /** 便捷工厂：tool 角色消息，回传某次工具调用的执行结果 */
    public static LlmMessage ofToolResult(String toolCallId, String resultText) {
        return LlmMessage.builder()
                .role("tool")
                .toolCallId(toolCallId)
                .contents(List.of(MessageContent.ofText(resultText)))
                .build();
    }

    /**
     * 判断是否为纯文本消息（contents 中全部为 TEXT 类型）
     */
    public boolean isTextOnly() {
        if (contents == null || contents.isEmpty()) return true;
        return contents.stream().allMatch(c -> c.getType() == ContentTypeEnum.TEXT);
    }

    /**
     * 纯文本消息时的快捷取值，返回第一个 TEXT content 的 value
     */
    public String getTextContent() {
        if (contents == null || contents.isEmpty()) return "";
        return contents.stream()
                .filter(c -> c.getType() == ContentTypeEnum.TEXT)
                .map(MessageContent::getValue)
                .findFirst()
                .orElse("");
    }
}

