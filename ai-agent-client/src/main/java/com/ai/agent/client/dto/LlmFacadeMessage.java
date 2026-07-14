package com.ai.agent.client.dto;

import java.io.Serializable;
import java.util.List;

/**
 * Dubbo 接口传输用的消息对象（轻量，不依赖 application 层）。
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>纯文本：{@code role + content}，向后兼容，无需改动现有调用方</li>
 *   <li>多模态：{@code role + contents}，支持文本、图片（URL/Base64）、文件、视频混合</li>
 * </ul>
 *
 * <h3>优先级规则</h3>
 * {@code contents} 非空时优先使用；{@code contents} 为空则回退到 {@code content} 纯文本。
 */
public class LlmFacadeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息角色：system / user / assistant */
    private String role;

    /**
     * 纯文本内容（向后兼容）。
     * 多模态场景请使用 {@link #contents}，两者同时存在时 {@code contents} 优先。
     */
    private String content;

    /**
     * 多模态内容块列表，支持文本、图片、文件、视频混合。
     * 非空时优先于 {@link #content} 使用。
     */
    private List<LlmFacadeContent> contents;

    public LlmFacadeMessage() {}

    public LlmFacadeMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /** 便捷工厂：system 消息（纯文本） */
    public static LlmFacadeMessage system(String content) {
        return new LlmFacadeMessage("system", content);
    }

    /** 便捷工厂：user 消息（纯文本） */
    public static LlmFacadeMessage user(String content) {
        return new LlmFacadeMessage("user", content);
    }

    /** 便捷工厂：assistant 消息（纯文本） */
    public static LlmFacadeMessage assistant(String content) {
        return new LlmFacadeMessage("assistant", content);
    }

    /** 便捷工厂：user 多模态消息 */
    public static LlmFacadeMessage userMultimodal(List<LlmFacadeContent> contents) {
        LlmFacadeMessage msg = new LlmFacadeMessage();
        msg.role = "user";
        msg.contents = contents;
        return msg;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<LlmFacadeContent> getContents() { return contents; }
    public void setContents(List<LlmFacadeContent> contents) { this.contents = contents; }
}

