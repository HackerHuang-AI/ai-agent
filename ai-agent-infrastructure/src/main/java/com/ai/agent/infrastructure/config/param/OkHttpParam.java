package com.ai.agent.infrastructure.config.param;

import lombok.Data;

/**
 * @Description: OkHttp 客户端参数配置实体，对应 Nacos ai-agent-http.json 中的各配置块（通用结构）。
 *
 *               <p>同一个类用于三种场景，语义由 key 决定：
 *               <ul>
 *                 <li>{@code "okhttp"} 块：全局连接参数（普通请求兜底）</li>
 *                 <li>{@code "llm"} 块：LLM 全局超时兜底（readTimeoutSeconds 建议 120s+）</li>
 *                 <li>平台名 key（如 {@code "doubao"}）：平台专属超时，仅需配置与 llm 块不同的字段</li>
 *               </ul>
 *
 *               <p>字段缺失时由 OkHttpConfig 按 平台 → llm → okhttp → 代码默认值 顺序兜底。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.config.param
 * @ClassName: OkHttpParam
 * @Author: HUANGcong
 * @Date: Created in 2026/7/8
 * @Version: 1.0
 */
@Data
public class OkHttpParam {

    /** 连接超时（秒） */
    private int connectTimeoutSeconds = 5;

    /** 普通请求读取超时（秒） */
    private int readTimeoutSeconds = 15;

    /** 写入超时（秒） */
    private int writeTimeoutSeconds = 10;

    /** 连接池最大空闲连接数 */
    private int maxIdleConnections = 50;

    /** 连接池保活时间（分钟） */
    private int keepAliveMinutes = 5;

    /**
     * 代理配置，不配置则不走代理。
     * 仅在平台专属块中配置，如 gemini / anthropic 等需要代理的平台。
     */
    private ProxyParam proxy;

    @Data
    public static class ProxyParam {
        /** 代理类型：HTTP 或 SOCKS */
        private String type = "HTTP";
        /** 代理服务器地址 */
        private String host;
        /** 代理服务器端口 */
        private int port;
        /** 代理认证用户名（可选，无需认证时不填） */
        private String username;
        /** 代理认证密码（可选，无需认证时不填） */
        private String password;
    }
}

