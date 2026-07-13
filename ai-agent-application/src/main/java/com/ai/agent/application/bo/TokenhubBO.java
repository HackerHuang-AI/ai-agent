package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 腾讯 TokenHub 平台默认凭证配置 BO，从 Nacos ai-agent-tokenhub.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-tokenhub.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://tokenhub.tencentmaas.com/v1/chat/completions",
 *     "modelCode": "hunyuan-turbos"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: TokenhubBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class TokenhubBO {

    /** API Key */
    private String apiKey;

    /** 接口地址，如 https://tokenhub.tencentmaas.com/v1/chat/completions */
    private String endpoint;

    /** 模型标识，如 hunyuan-turbos / hunyuan-lite */
    private String modelCode;
}

