package com.ai.agent.infrastructure.config.param;

import lombok.Data;

/**
 * @Description: OkHttp 客户端参数配置实体，对应 Nacos ai-agent-http.json 中的配置块。
 *               <ul>
 *                 <li>{@code "okhttp"} 块：全局连接参数（超时、连接池）</li>
 *                 <li>平台名 key（如 {@code "doubao"}）：平台专属超时覆盖，仅需配置与全局不同的字段</li>
 *               </ul>
 *               字段缺失时由 OkHttpConfig 兜底使用默认值。
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

    /** LLM 专用读取超时（秒），适应大模型慢响应 */
    private int llmReadTimeoutSeconds = 120;

    /** LLM 专用写入超时（秒） */
    private int llmWriteTimeoutSeconds = 30;

    /** LLM 专用连接超时（秒） */
    private int llmConnectTimeoutSeconds = 10;

    /** 连接池最大空闲连接数 */
    private int maxIdleConnections = 50;

    /** 连接池保活时间（分钟） */
    private int keepAliveMinutes = 5;
}

