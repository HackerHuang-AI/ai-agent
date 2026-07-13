package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: Anthropic（Claude）平台默认凭证配置 BO，从 Nacos ai-agent-anthropic.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-anthropic.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "sk-ant-xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://api.anthropic.com/v1/messages",
 *     "modelCode": "claude-3-5-sonnet-20241022"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: AnthropicBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class AnthropicBO {

    /** API Key，格式：sk-ant-xxxxxxxxxxxxxxxx */
    private String apiKey;

    /** 接口地址，如 https://api.anthropic.com/v1/messages */
    private String endpoint;

    /** 模型标识，如 claude-3-5-sonnet-20241022 / claude-3-haiku-20240307 */
    private String modelCode;
}

