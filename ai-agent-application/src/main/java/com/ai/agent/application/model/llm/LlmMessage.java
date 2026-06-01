package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: LLM 对话消息，对应 OpenAI messages 数组中的单条消息
 *               role 枚举：system / user / assistant / tool
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

    /** 消息内容 */
    private String content;
}

