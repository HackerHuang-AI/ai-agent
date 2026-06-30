package com.ai.agent.client.dto;

import java.io.Serializable;

/**
 * Dubbo 接口传输用的消息对象（轻量，不依赖 application 层）
 * <p>纯文本场景：role + content 即可；多模态暂不透过 Dubbo 传递</p>
 */
public class LlmFacadeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息角色：system / user / assistant */
    private String role;

    /** 纯文本内容 */
    private String content;

    public LlmFacadeMessage() {}

    public LlmFacadeMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /** 便捷工厂：system 消息 */
    public static LlmFacadeMessage system(String content) {
        return new LlmFacadeMessage("system", content);
    }

    /** 便捷工厂：user 消息 */
    public static LlmFacadeMessage user(String content) {
        return new LlmFacadeMessage("user", content);
    }

    /** 便捷工厂：assistant 消息 */
    public static LlmFacadeMessage assistant(String content) {
        return new LlmFacadeMessage("assistant", content);
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}

