package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: Google Gemini 平台默认凭证配置 BO，从 Nacos ai-agent-gemini.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-gemini.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "AIzaSyxxxxxxxxxxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
 *     "modelCode": "gemini-2.0-flash"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: GeminiBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class GeminiBO {

    /** API Key，格式：AIzaSy... */
    private String apiKey;

    /** 接口地址，如 https://generativelanguage.googleapis.com/v1beta/openai/chat/completions */
    private String endpoint;

    /** 模型标识，如 gemini-2.0-flash / gemini-1.5-pro */
    private String modelCode;
}

