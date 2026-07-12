package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 智谱 GLM 平台默认凭证配置 BO，从 Nacos ai-agent-zhipu.json 中读取。
 *               调用方若未传 apiKey / endpoint / modelCode，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-zhipu.json）：
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
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: ZhipuBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/12
 * @Version: 1.0
 */
@Data
public class ZhipuBO {

    /** API Key，格式：{id}.{secret} */
    private String apiKey;

    /** 接口地址，如 https://open.bigmodel.cn/api/paas/v4/chat/completions */
    private String endpoint;

    /** 模型标识，如 glm-4-flash / glm-4-air / glm-4 */
    private String modelCode;
}

