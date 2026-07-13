package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 腾讯混元 Token Plan 平台默认凭证配置 BO，从 Nacos ai-agent-hy-tokenplan.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-hy-tokenplan.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://api.lkeap.cloud.tencent.com/plan/v3/chat/completions",
 *     "modelCode": "hunyuan-turbos-latest"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: HyTokenPlanBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class HyTokenPlanBO {

    /** API Key */
    private String apiKey;

    /** 接口地址，如 https://api.lkeap.cloud.tencent.com/plan/v3/chat/completions */
    private String endpoint;

    /** 模型标识，如 hunyuan-turbos-latest / hunyuan-lite */
    private String modelCode;
}

