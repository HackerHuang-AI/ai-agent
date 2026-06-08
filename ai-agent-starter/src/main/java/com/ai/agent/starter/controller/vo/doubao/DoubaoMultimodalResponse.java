package com.ai.agent.starter.controller.vo.doubao;

import lombok.Builder;
import lombok.Data;

/**
 * @Description: 豆包多模态对话响应 VO（Responses API）
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.doubao
 * @ClassName: DoubaoMultimodalResponse
 * @Author: HUANGcong
 * @Date: Created in 2026/6/8
 * @Version: 1.0
 */
@Data
@Builder
public class DoubaoMultimodalResponse {

    /** 模型回复的文本内容 */
    private String content;

    /** 实际使用的模型接入点 ID */
    private String model;

    /** 输入 token 数 */
    private int inputTokens;

    /** 输出 token 数 */
    private int outputTokens;
}

