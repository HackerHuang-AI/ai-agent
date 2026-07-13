package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: Ollama 本地部署平台默认配置 BO，从 Nacos ai-agent-ollama.json 中读取。
 *               Ollama 为本地部署，无需 apiKey（可传空），endpoint 指向本地服务地址。
 *
 * <p>Nacos 配置示例（ai-agent-ollama.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "ollama",
 *     "endpoint":  "http://localhost:11434/v1/chat/completions",
 *     "modelCode": "llama3.2"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: OllamaBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class OllamaBO {

    /** API Key，Ollama 本地无鉴权时填 "ollama" 即可 */
    private String apiKey;

    /** 接口地址，如 http://localhost:11434/v1/chat/completions */
    private String endpoint;

    /** 模型标识，如 llama3.2 / qwen2.5 / deepseek-r1 */
    private String modelCode;
}

