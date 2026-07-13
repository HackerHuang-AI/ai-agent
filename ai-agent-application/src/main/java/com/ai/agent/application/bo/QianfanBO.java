package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 百度千帆平台默认凭证配置 BO，从 Nacos ai-agent-qianfan.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-qianfan.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "bce-v3/xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://qianfan.baidubce.com/v2/chat/completions",
 *     "modelCode": "ernie-4.5-8k"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: QianfanBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class QianfanBO {

    /** API Key，格式：bce-v3/... */
    private String apiKey;

    /** 接口地址，如 https://qianfan.baidubce.com/v2/chat/completions */
    private String endpoint;

    /** 模型标识，如 ernie-4.5-8k / ernie-lite-8k */
    private String modelCode;
}

