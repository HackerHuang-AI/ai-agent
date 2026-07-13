package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 小米 MiMo 平台默认凭证配置 BO，从 Nacos ai-agent-mimo.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-mimo.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "sk-xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://api.xiaomimimo.com/v1/chat/completions",
 *     "modelCode": "MiMo-7B-RL"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: MimoBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class MimoBO {

    /** API Key */
    private String apiKey;

    /** 接口地址，如 https://api.xiaomimimo.com/v1/chat/completions */
    private String endpoint;

    /** 模型标识，如 MiMo-7B-RL */
    private String modelCode;
}

