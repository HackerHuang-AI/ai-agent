package com.ai.agent.infrastructure.config;

import com.ai.agent.infrastructure.config.param.RetryParam;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * @Description: 重试策略动态配置中心，独立于 OkHttp 连接配置。
 *
 * <p>设计要点：
 * <ul>
 *   <li>重试参数读时生效，无需重建任何对象，Nacos 变更后下一次调用即使用新参数</li>
 *   <li>查找顺序：平台专属 key → 全局 LLM key {@code "llm"} → 通用 key {@code "default"} → 代码默认值</li>
 *   <li>与 OkHttp 连接配置完全解耦，调用方只关心"失败了怎么办"，无需感知 HTTP 客户端细节</li>
 * </ul>
 *
 * <p>Nacos 配置示例（ai-agent-retry.json）：
 * <pre>{@code
 * {
 *   "default": { "maxRetries": 3, "intervalMs": 500,  "backoffMultiplier": 2.0, "maxWaitMs": 30000 },
 *   "llm":     { "maxRetries": 2, "intervalMs": 1000, "backoffMultiplier": 2.0, "maxWaitMs": 60000 },
 *   "doubao":  { "maxRetries": 3, "intervalMs": 500,  "backoffMultiplier": 1.5, "maxWaitMs": 30000 },
 *   "deepseek":{ "maxRetries": 1, "intervalMs": 2000, "backoffMultiplier": 2.0, "maxWaitMs": 60000 }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.config
 * @ClassName: RetryConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/7/9
 * @Version: 1.0
 */
@Slf4j
@Component
public class RetryConfig {

    // ==================== Nacos key，与 ai-agent-retry.json 中的 key 对应 ====================
    private static final String RETRY_DEFAULT_KEY = "default";
    private static final String RETRY_LLM_KEY     = "llm";

    // ==================== 默认参数（Nacos 未配置时兜底）====================
    private static final RetryParam DEFAULT_RETRY_PARAM = new RetryParam();
    private static final RetryParam DEFAULT_LLM_RETRY_PARAM;

    static {
        DEFAULT_LLM_RETRY_PARAM = new RetryParam();
        DEFAULT_LLM_RETRY_PARAM.setMaxRetries(2);
        DEFAULT_LLM_RETRY_PARAM.setIntervalMs(1000);
        DEFAULT_LLM_RETRY_PARAM.setBackoffMultiplier(2.0);
        DEFAULT_LLM_RETRY_PARAM.setMaxWaitMs(60_000);
    }

    @Autowired
    private NacosConfig nacosConfig;

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        nacosConfig.addListener(NacosDataIdEnum.AI_AGENT_RETRY.dataId(), new Listener() {
            @Override
            public Executor getExecutor() { return null; }

            @Override
            public void receiveConfigInfo(String configInfo) {
                // 重试参数读时生效，此处仅记录日志，无需手动刷新任何缓存
                log.info("[RetryConfig] 收到 ai-agent-retry.json 变更，重试参数下次调用自动生效");
            }
        });
        log.info("[RetryConfig] 初始化完成，已注册 Nacos 监听器");
    }

    // ==================== 对外暴露：获取重试参数（读时生效）====================

    /**
     * 获取通用请求重试参数。
     * 读 Nacos {@code ai-agent-retry.json} 中的 {@code "default"} key，未配置时使用代码默认值。
     */
    public RetryParam getRetryParam() {
        RetryParam param = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_RETRY, RETRY_DEFAULT_KEY, RetryParam.class);
        if (param == null) {
            log.debug("[RetryConfig] Nacos 未配置 retry.default，使用默认值");
            return DEFAULT_RETRY_PARAM;
        }
        return param;
    }

    /**
     * 获取 LLM 请求重试参数（全局兜底）。
     * 读 Nacos {@code ai-agent-retry.json} 中的 {@code "llm"} key，未配置时使用代码默认值。
     */
    public RetryParam getLlmRetryParam() {
        RetryParam param = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_RETRY, RETRY_LLM_KEY, RetryParam.class);
        if (param == null) {
            log.debug("[RetryConfig] Nacos 未配置 retry.llm，使用默认值");
            return DEFAULT_LLM_RETRY_PARAM;
        }
        return param;
    }

    /**
     * 获取指定平台的 LLM 重试参数，支持按平台独立配置。
     *
     * <p>查找顺序（均读自 {@code ai-agent-retry.json}）：
     * <ol>
     *   <li>平台专属 key（如 {@code "doubao"}）</li>
     *   <li>全局 LLM 兜底 key {@code "llm"}</li>
     *   <li>代码默认值</li>
     * </ol>
     *
     * @param platform 平台标识（不区分大小写，与 LlmRouter 中的 platform 值一致）
     */
    public RetryParam getLlmRetryParam(String platform) {
        if (platform != null && !platform.isBlank()) {
            RetryParam platformParam = NacosConfigUtil.getObject(
                    NacosDataIdEnum.AI_AGENT_RETRY, platform.toLowerCase(), RetryParam.class);
            if (platformParam != null) {
                log.debug("[RetryConfig] 使用平台专属重试参数, platform={}", platform);
                return platformParam;
            }
        }
        // 无平台专属配置，fallback 到全局 llm 重试
        return getLlmRetryParam();
    }
}

