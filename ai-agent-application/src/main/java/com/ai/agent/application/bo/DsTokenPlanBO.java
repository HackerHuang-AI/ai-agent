package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 阿里云灵积 DashScope Token Plan 平台默认凭证配置 BO，从 Nacos ai-agent-dashscope-tokenplan.json 中读取。
 *
 * <p>Nacos 配置示例（ai-agent-dashscope-tokenplan.json）：
 * <pre>{@code
 * {
 *   "chat": {
 *     "apiKey":    "sk-xxxxxxxxxxxxxxxx",
 *     "endpoint":  "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions",
 *     "modelCode": "qwen-max"
 *   }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: DsTokenPlanBO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/13
 * @Version: 1.0
 */
@Data
public class DsTokenPlanBO {

    /** API Key */
    private String apiKey;

    /** 接口地址，如 https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions */
    private String endpoint;

    /** 模型标识，如 qwen-max / qwen-plus */
    private String modelCode;
}

