package com.ai.agent.application.bo;

import lombok.Data;

/**
 * MiniMax 平台默认凭证配置 BO，从 Nacos ai-agent-minimax.json 中读取。
 *               调用方若未传 apiKey / endpoint / modelCode，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-minimax.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
 *     "endpoint":  "https://api.minimaxi.com/v1/text/chatcompletion_v2",
 *     "modelCode": "abab6.5s-chat"
 *   }
 * }
 * }</pre>
 *
 * @author HUANGcong
 * @since 2026-07-12
 */
@Data
public class MiniMaxBO {

    /** API Key */
    private String apiKey;

    /** 接口地址，如 https://api.minimaxi.com/v1/text/chatcompletion_v2 */
    private String endpoint;

    /** 模型标识，如 abab6.5s-chat / abab7-preview / MiniMax-Text-01 */
    private String modelCode;
}

