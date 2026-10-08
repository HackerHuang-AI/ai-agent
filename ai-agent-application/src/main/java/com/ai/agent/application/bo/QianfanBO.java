package com.ai.agent.application.bo;

import lombok.Data;

/**
 * 百度千帆平台默认凭证配置 BO，从 Nacos ai-agent-qianfan.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-qianfan.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "bce-v3/ALTAK-xxx/yyy",
 *     "endpoint":  "https://qianfan.baidubce.com/v2/chat/completions",
 *     "modelCode": "ernie-4.5-8k"
 *   }
 * }
 * }</pre>
 *
 * @author HUANGcong
 * @since 2026-07-13
 */
@Data
public class QianfanBO {

    /** API Key，格式：bce-v3/ALTAK-xxx/yyy，在千帆控制台「API Key管理」处获取完整字符串 */
    private String apiKey;

    /** 接口地址，如 https://qianfan.baidubce.com/v2/chat/completions */
    private String endpoint;

    /** 模型标识，如 ernie-4.5-8k / ernie-speed-8k */
    private String modelCode;
}

