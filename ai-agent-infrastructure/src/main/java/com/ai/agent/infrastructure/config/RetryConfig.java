package com.ai.agent.infrastructure.config;

import com.ai.agent.infrastructure.config.param.RetryParam;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.enums.RetryConfigEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @Description: 重试策略动态配置中心，独立于 OkHttp 连接配置。
 *
 * <p>设计要点：
 * <ul>
 *   <li>平台与 Nacos key 的映射由 {@link RetryConfigEnum} 枚举统一管理，新增平台只需加一行枚举项</li>
 *   <li>两层兜底：① Nacos {@code ai-agent-retry.json} 可配兜底；② 代码内置默认值不可配兜底</li>
 *   <li>重试参数读时生效，无需重建任何对象，Nacos 变更后下一次调用即使用新参数</li>
 *   <li>缓存更新由 {@link NacosConfig} 统一负责，本类无需额外注册监听器</li>
 *   <li>与 OkHttp 连接配置完全解耦，调用方只关心"失败了怎么办"</li>
 * </ul>
 *
 * <p>查找链路：
 * <pre>
 *   platform → RetryConfigEnum.of(platform) → 枚举项（找不到返回 DEFAULT）
 *     → def.nacosKey → Nacos 读取
 *     → 读不到 → 代码内置默认值
 * </pre>
 *
 * <p>Nacos 配置示例（ai-agent-retry.json）：
 * <pre>{@code
 * {
 *   "default": { "maxRetries": 3, "intervalMs": 500,  "backoffMultiplier": 2.0, "maxWaitMs": 30000 },
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

    /** 通用请求重试的 Nacos key（非 LLM 场景） */
    private static final String RETRY_DEFAULT_KEY = "default";

    /** 代码内置兜底参数（两层兜底的第二层，不可配置） */
    private static final RetryParam DEFAULT_RETRY_PARAM = new RetryParam();

    // ==================== 对外暴露 ====================

    /**
     * 获取通用请求重试参数（无平台场景）。
     *
     * <p>读 Nacos {@code ai-agent-retry.json} 中的 {@code "default"} key，
     * 未配置时使用代码内置默认值兜底。
     */
    public RetryParam getDefaultRetryParam() {
        RetryParam param = NacosConfigUtil.getObject(
                NacosDataIdEnum.AI_AGENT_RETRY, RETRY_DEFAULT_KEY, RetryParam.class);
        if (param == null) {
            log.debug("[RetryConfig] Nacos 未配置 retry.default，使用代码默认值");
            return DEFAULT_RETRY_PARAM;
        }
        return param;
    }

    /**
     * 获取指定平台的重试参数。
     *
     * <p>查找链路：
     * <ol>
     *   <li>{@link RetryConfigEnum#of(String)} 查找枚举项，找不到返回 {@link RetryConfigEnum#DEFAULT}</li>
     *   <li>用枚举项的 {@code nacosKey} 去 Nacos {@code ai-agent-retry.json} 读配置</li>
     *   <li>Nacos 未配置时使用代码内置默认值兜底</li>
     * </ol>
     *
     * @param platform 平台标识（不区分大小写，与 LlmRouter 中的 platform 值一致）
     */
    public RetryParam getRetryParam(String platform) {
        RetryConfigEnum def = RetryConfigEnum.of(platform);
        log.debug("[RetryConfig] platform={} → nacosKey={}", platform, def.nacosKey);
        RetryParam param = NacosConfigUtil.getObject(
                NacosDataIdEnum.AI_AGENT_RETRY, def.nacosKey, RetryParam.class);
        if (param == null) {
            log.debug("[RetryConfig] Nacos 未配置 retry.{}，使用代码默认值", def.nacosKey);
            return DEFAULT_RETRY_PARAM;
        }
        return param;
    }
}

