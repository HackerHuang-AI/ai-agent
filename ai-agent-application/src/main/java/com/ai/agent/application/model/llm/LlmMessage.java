package com.ai.agent.application.model.llm;

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
     * 多模态内容列表，至少含一个元素。
     * 纯文本消息：列表中只有一个 ContentType.TEXT 元素。
     * 多模态消息：文本 + 图片/文件/视频 的组合。
     */
    private List<MessageContent> contents;

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

    /**
     * 判断是否为纯文本消息（contents 中全部为 TEXT 类型）
     */
    public boolean isTextOnly() {
        if (contents == null || contents.isEmpty()) return true;
        return contents.stream().allMatch(c -> c.getType() == com.ai.agent.application.enums.ContentType.TEXT);
    }

    /**
     * 纯文本消息时的快捷取值，返回第一个 TEXT content 的 value
     */
    public String getTextContent() {
        if (contents == null || contents.isEmpty()) return "";
        return contents.stream()
                .filter(c -> c.getType() == com.ai.agent.application.enums.ContentType.TEXT)
                .map(MessageContent::getValue)
                .findFirst()
                .orElse("");
    }
}

