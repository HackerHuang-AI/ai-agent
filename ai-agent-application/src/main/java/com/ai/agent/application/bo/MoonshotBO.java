package com.ai.agent.application.bo;

import lombok.Data;

/**
 * Moonshot（Kimi）平台默认凭证配置 BO，从 Nacos ai-agent-moonshot.json 中读取。
 *               调用方若未传 apiKey / endpoint / modelCode，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-moonshot.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "sk-xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://api.moonshot.cn/v1/chat/completions",
 *     "modelCode": "moonshot-v1-8k"
 *   }
 * }
 * }</pre>
 *
 * @author HUANGcong
 * @since 2026-07-12
 */
@Data
public class MoonshotBO {

    /** API Key，格式：sk-xxxxxxxxxxxxxxxx */
    private String apiKey;

    /** 接口地址，如 https://api.moonshot.cn/v1/chat/completions */
    private String endpoint;

    /** 模型标识，如 moonshot-v1-8k / moonshot-v1-32k / moonshot-v1-128k */
    private String modelCode;
}

