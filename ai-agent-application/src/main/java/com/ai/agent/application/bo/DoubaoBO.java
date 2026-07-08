package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 豆包平台默认凭证配置 BO，从 Nacos ai-agent-doubao.json 中读取。
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
 *   "multimodal": {
 *     "apiKey":    "ark-xxx",
 *     "endpoint":  "https://ark.cn-beijing.volces.com/api/v3/responses",
 *     "model":     "ep-xxx"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: DoubaoConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
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

