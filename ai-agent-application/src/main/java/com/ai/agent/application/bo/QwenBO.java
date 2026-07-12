package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: Qwen（阿里云百炼）平台默认凭证配置 BO，从 Nacos ai-agent-qwen.json 中读取。
 *               调用方若未传 apiKey / endpoint / modelCode，Service 层从此处兜底。
 *
 * <p>Nacos 配置示例（ai-agent-qwen.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "sk-xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
 *     "modelCode": "qwen-max"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: QwenBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/12
 * @Version: 1.0
 */
@Data
public class QwenBO {

    /** API Key，格式：sk-xxxxxxxxxxxxxxxx */
    private String apiKey;

    /** 接口地址，如 https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions */
    private String endpoint;

    /** 模型标识，如 qwen-max / qwen-plus / qwen-turbo */
    private String modelCode;
}

