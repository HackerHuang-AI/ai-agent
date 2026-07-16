package com.ai.agent.infrastructure.config.param;

import lombok.Data;

import java.util.Collections;
import java.util.List;

/**
 * @Description: 重试参数配置实体，对应 Nacos ai-agent-retry.json 中各平台配置块。
 *               字段缺失时由 RetryConfig 兜底使用默认值。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.config.param
 * @ClassName: RetryParam
 * @Author: HUANGcong
 * @Date: Created in 2026/7/8
 * @Version: 1.0
 */
@Data
public class RetryParam {

    /** 最大重试次数（不含首次调用） */
    private int maxRetries = 3;

    /** 首次重试前基础等待时间（毫秒） */
    private long intervalMs = 500;

    /**
     * 指数退避乘数。
     * 第 n 次重试等待时间 = intervalMs * multiplier^(n-1)
     * 例：multiplier=2，interval=500ms → 500ms / 1000ms / 2000ms
     */
    private double backoffMultiplier = 2.0;

    /**
     * 单次等待上限（毫秒），防止退避时间无限增长。
     * 默认 30s，LLM 场景可适当调大。
     */
    private long maxWaitMs = 30_000;

    /**
     * 不触发重试的业务错误码列表（对应 ErrorCodeEnum.code 字段值，如 "2002009"）。
     *
     * <p>当 BizException 携带的错误码在此列表中时，RetryUtil 直接向上抛出，不做重试。
     * 列表为空或未配置时，所有 BizException 均触发重试（原有行为不变）。
     *
     * <p>Nacos 配置示例：
     * <pre>{@code
     * "deepseek": {
     *   "maxRetries": 1,
     *   "nonRetryableCodes": ["2002009", "2001001", "2002010"]
     * }
     * }</pre>
     */
    private List<String> nonRetryableCodes = Collections.emptyList();
}

