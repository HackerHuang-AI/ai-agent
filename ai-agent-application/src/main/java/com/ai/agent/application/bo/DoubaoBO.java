package com.ai.agent.application.bo;

import lombok.Data;

/**
 * 豆包平台默认凭证配置 BO，从 Nacos ai-agent-doubao.json 中读取。
 *               调用方若未传 apiKey / endpoint / model，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-doubao.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":     "ark-xxx",
 *     "endpoint":   "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
 *     "endpointId": "ep-xxx"
 *   },
 *   "responses": {
 *     "apiKey":    "ark-xxx",
 *     "endpoint":  "https://ark.cn-beijing.volces.com/api/v3/responses",
 *     "model":     "ep-xxx"
 *   }
 * }
 * }</pre>
 *
 * @author HUANGcong
 * @since 2026-06-28
 */
@Data
public class DoubaoBO {

    /** API Key */
    private String apiKey;

    /** 接口地址 */
    private String endpoint;

    /** Chat Completions 接口模型接入点 ID（endpointId） */
    private String endpointId;

    /** Responses API 接口模型接入点 ID（model） */
    private String model;
}

