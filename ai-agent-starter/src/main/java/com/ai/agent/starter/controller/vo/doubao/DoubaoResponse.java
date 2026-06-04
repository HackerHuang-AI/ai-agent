package com.ai.agent.starter.controller.vo.doubao;

import lombok.Builder;
import lombok.Data;

/**
 * @Description: 豆包平台对话响应 VO
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.doubao
 * @ClassName: DoubaoResponse
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Data
@Builder
public class DoubaoResponse {

    /** 模型回复的文本内容 */
    private String content;

    /** 实际使用的模型接入点 ID */
    private String endpointId;

    /** 输入 token 数（prompt_tokens） */
    private int inputTokens;

    /** 输出 token 数（completion_tokens） */
    private int outputTokens;

    /** 结束原因：stop / length / content_filter */
    private String finishReason;
}

