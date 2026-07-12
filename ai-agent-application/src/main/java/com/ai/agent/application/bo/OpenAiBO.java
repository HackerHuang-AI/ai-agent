package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: OpenAI 平台默认凭证配置 BO，从 Nacos ai-agent-openai.json 中读取。
 *               调用方若未传 apiKey / endpoint / modelCode，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-openai.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "sk-xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://api.openai.com/v1/chat/completions",
 *     "modelCode": "gpt-4.1"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: OpenAiBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/12
 * @Version: 1.0
 */
@Data
public class OpenAiBO {

    /** API Key，格式：sk-xxxxxxxxxxxxxxxx */
    private String apiKey;

    /** 接口地址，如 https://api.openai.com/v1/chat/completions */
    private String endpoint;

    /** 模型标识，如 gpt-4.1 / gpt-4o-mini */
    private String modelCode;
}

