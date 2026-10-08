package com.ai.agent.application.bo;

import lombok.Data;

/**
 * 智谱 GLM 平台默认凭证配置 BO，从 Nacos ai-agent-glm.json 中读取。
 *               调用方若未传 apiKey / endpoint / modelCode，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-glm.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "xxxxxxxxxxxxxxxx.xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://open.bigmodel.cn/api/paas/v4/chat/completions",
 *     "modelCode": "glm-4-flash"
 *   }
 * }
 * }</pre>
 *
 * @author HUANGcong
 * @since 2026-07-12
 */
@Data
public class GlmBO {

    /** API Key，格式：{id}.{secret} */
    private String apiKey;

    /** 接口地址，如 https://open.bigmodel.cn/api/paas/v4/chat/completions */
    private String endpoint;

    /** 模型标识，如 glm-4-flash / glm-4-air / glm-4 */
    private String modelCode;
}

