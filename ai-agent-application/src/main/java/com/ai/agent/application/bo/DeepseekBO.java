package com.ai.agent.application.bo;

import lombok.Data;

/**
 * Deepseek 平台默认凭证配置 BO，从 Nacos ai-agent-deepseek.json 中读取。
 *               调用方若未传 apiKey / endpoint / modelCode，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-deepseek.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "sk-xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://api.deepseek.com/chat/completions",
 *     "modelCode": "deepseek-chat"
 *   }
 * }
 * }</pre>
 *
 * @author HUANGcong
 * @since 2026-07-11
 */
@Data
public class DeepseekBO {

    /** API Key，格式：sk-xxxxxxxxxxxxxxxx */
    private String apiKey;

    /** 接口地址，如 https://api.deepseek.com/chat/completions */
    private String endpoint;

    /** 模型标识，如 deepseek-chat / deepseek-reasoner */
    private String modelCode;
}

