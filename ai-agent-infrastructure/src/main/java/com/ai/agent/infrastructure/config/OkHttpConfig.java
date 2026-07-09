package com.ai.agent.infrastructure.config;

import com.ai.agent.infrastructure.config.param.OkHttpParam;
import com.ai.agent.infrastructure.config.param.RetryParam;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @Description: OkHttp 客户端动态配置中心。
 *
 * <p>设计要点：
 * <ul>
 *   <li>单连接池：只维护一个基础 Client，连接池参数（maxIdleConnections / keepAliveMinutes）变更时重建</li>
 *   <li>{@link #getLlmClient()} / {@link #getLlmClient(String)} 每次调用时在基础 Client 上 newBuilder()
 *       叠加最新超时参数返回，连接池始终只有一个，无切换抖动</li>
 *   <li>OkHttpClient.newBuilder() 复用父 Client 的连接池，不会创建新连接池</li>
 *   <li>OkHttp 连接参数来自 {@code ai-agent-http.json}；重试参数独立存放于 {@code ai-agent-retry.json}</li>
 *   <li>重试参数读时生效，无需重建任何对象；平台专属超时同样支持按需读取</li>
 * </ul>
 *
 * <p>Nacos 配置示例（ai-agent-http.json）—— 只放网络连接参数：
 * <pre>{@code
 * {
 *   "okhttp": { "connectTimeoutSeconds": 5, "readTimeoutSeconds": 15, "writeTimeoutSeconds": 10, "maxIdleConnections": 50, "keepAliveMinutes": 5 },
 *   "llm":    { "readTimeoutSeconds": 120, "writeTimeoutSeconds": 30, "connectTimeoutSeconds": 10 },
 *   "doubao": { "readTimeoutSeconds": 180 },
 *   "deepseek": { "readTimeoutSeconds": 60 }
 * }
 * }</pre>
 *
 * <p>Nacos 配置示例（ai-agent-retry.json）—— 只放重试策略：
 * <pre>{@code
 * {
 *   "default": { "maxRetries": 3, "intervalMs": 500, "backoffMultiplier": 2.0, "maxWaitMs": 30000 },
 *   "llm":     { "maxRetries": 2, "intervalMs": 1000, "backoffMultiplier": 2.0, "maxWaitMs": 60000 },
 *   "doubao":  { "maxRetries": 3, "intervalMs": 500,  "backoffMultiplier": 1.5, "maxWaitMs": 30000 },
 *   "deepseek":{ "maxRetries": 1, "intervalMs": 2000, "backoffMultiplier": 2.0, "maxWaitMs": 60000 }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.config
 * @ClassName: OkHttpConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/7/8
 * @Version: 2.0
 */
@Slf4j
@Component
public class OkHttpConfig {

    // ==================== Nacos key ====================
    /** ai-agent-http.json 中各超时块的 key */
    private static final String OKHTTP_KEY        = "okhttp";
    private static final String LLM_KEY           = "llm";
    /** ai-agent-retry.json 中各重试块的 key */
    private static final String RETRY_DEFAULT_KEY = "default";
    private static final String RETRY_LLM_KEY     = "llm";

    // ==================== 默认参数（Nacos 未配置时兜底）====================
    private static final OkHttpParam DEFAULT_OKHTTP_PARAM   = new OkHttpParam();
    private static final RetryParam  DEFAULT_RETRY_PARAM    = new RetryParam();
    private static final RetryParam  DEFAULT_LLM_RETRY_PARAM;

    static {
        DEFAULT_LLM_RETRY_PARAM = new RetryParam();
        DEFAULT_LLM_RETRY_PARAM.setMaxRetries(2);
        DEFAULT_LLM_RETRY_PARAM.setIntervalMs(1000);
        DEFAULT_LLM_RETRY_PARAM.setBackoffMultiplier(2.0);
        DEFAULT_LLM_RETRY_PARAM.setMaxWaitMs(60_000);
    }

    @Autowired
    private NacosConfig nacosConfig;

    /**
     * 基础 Client（持有连接池）。
     * 连接池参数（maxIdleConnections / keepAliveMinutes）变更时重建此 Client。
     * 超时参数变更时不重建，通过 getLlmClient() 的 newBuilder() 动态叠加。
     */
    private final AtomicReference<OkHttpClient> baseClientRef = new AtomicReference<>();

    /**
     * 当前生效的 OkHttp 参数缓存。
     * getClient() / getLlmClient() 直接读此缓存，避免每次请求触发 JSON 反序列化。
     * Nacos 回调时原子更新，读写均无锁。
     */
    private final AtomicReference<OkHttpParam> currentParamRef = new AtomicReference<>();

    /** 上次构建基础 Client 时使用的连接池参数，用于热更新时判断是否需要重建 */
    private volatile OkHttpParam lastPoolParam;

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        OkHttpParam param = readOkHttpParam();
        currentParamRef.set(param);
        baseClientRef.set(buildBaseClient(param));
        lastPoolParam = param;
        log.info("[OkHttpConfig] 初始化完成，okhttp param={}", param);
        registerNacosListener();
    }

    // ==================== 对外暴露：获取 Client ====================

    /**
     * 获取通用 OkHttpClient（readTimeout=readTimeoutSeconds）。
     * 每次调用在基础 Client 上 newBuilder() 叠加当前超时参数，共享同一连接池。
     */
    public OkHttpClient getClient() {
        OkHttpParam p = currentParamRef.get();
        return baseClientRef.get().newBuilder()
                .connectTimeout(p.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(p.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(p.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取 LLM 专用 OkHttpClient，使用 {@code "llm"} 全局超时块。
     * 每次调用实时读 Nacos 缓存，Nacos 热更新后下一次请求即生效，无连接抖动。
     */
    public OkHttpClient getLlmClient() {
        OkHttpParam p = readLlmOkHttpParam();
        return baseClientRef.get().newBuilder()
                .connectTimeout(p.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(p.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(p.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取指定平台的 LLM OkHttpClient，支持按平台独立配置超时参数。
     *
     * <p>查找顺序（均读自 {@code ai-agent-http.json}）：
     * <ol>
     *   <li>平台专属 key（如 {@code "doubao"}）—— 仅需配置与 llm 块不同的字段</li>
     *   <li>全局 LLM 兜底 key {@code "llm"}</li>
     *   <li>代码默认值</li>
     * </ol>
     *
     * @param platform 平台标识（不区分大小写，与 LlmRouter 中的 platform 值一致）
     */
    public OkHttpClient getLlmClient(String platform) {
        if (platform != null && !platform.isBlank()) {
            OkHttpParam platformParam = NacosConfigUtil.getObject(
                    NacosDataIdEnum.AI_AGENT_HTTP, platform.toLowerCase(), OkHttpParam.class);
            if (platformParam != null) {
                log.debug("[OkHttpConfig] 使用平台专属超时参数, platform={}", platform);
                return baseClientRef.get().newBuilder()
                        .connectTimeout(platformParam.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                        .readTimeout(platformParam.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                        .writeTimeout(platformParam.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                        .build();
            }
        }
        // 无平台专属配置，fallback 到全局 LLM 超时
        return getLlmClient();
    }

    // ==================== 对外暴露：获取重试参数（读 ai-agent-retry.json，读时生效）====================

    /**
     * 获取通用请求重试参数。
     * 读 Nacos {@code ai-agent-retry.json} 中的 {@code "default"} key，未配置时使用代码默认值。
     */
    public RetryParam getRetryParam() {
        RetryParam param = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_RETRY, RETRY_DEFAULT_KEY, RetryParam.class);
        if (param == null) {
            log.debug("[OkHttpConfig] Nacos 未配置 retry.default，使用默认值");
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
            log.debug("[OkHttpConfig] Nacos 未配置 retry.llm，使用默认值");
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
                log.debug("[OkHttpConfig] 使用平台专属重试参数, platform={}", platform);
                return platformParam;
            }
        }
        // 无平台专属配置，fallback 到全局 llm 重试
        return getLlmRetryParam();
    }

    // ==================== Nacos 热更新 ====================

    private void registerNacosListener() {
        // 监听 OkHttp 连接参数变更（ai-agent-http.json）
        nacosConfig.addListener(NacosDataIdEnum.AI_AGENT_HTTP.dataId(), new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                log.info("[OkHttpConfig] 收到 ai-agent-http.json 变更");
                OkHttpParam newParam = readOkHttpParam();
                // 先更新参数缓存，getClient()/getLlmClient() 下次调用即生效
                currentParamRef.set(newParam);
                // 用上次构建时的参数对比，判断连接池参数是否真正变更
                if (poolParamChanged(lastPoolParam, newParam)) {
                    OkHttpClient newBase = buildBaseClient(newParam);
                    OkHttpClient oldBase = baseClientRef.getAndSet(newBase);
                    evict(oldBase);
                    log.info("[OkHttpConfig] 连接池参数变更，基础 Client 已重建: maxIdle {} → {}, keepAlive {}m → {}m",
                            lastPoolParam.getMaxIdleConnections(), newParam.getMaxIdleConnections(),
                            lastPoolParam.getKeepAliveMinutes(), newParam.getKeepAliveMinutes());
                    lastPoolParam = newParam;
                } else {
                    log.info("[OkHttpConfig] 超时参数变更，下次请求自动生效，无需重建 Client");
                }
            }
        });
        // 监听重试策略变更（ai-agent-retry.json）——重试参数读时生效，此处仅记录日志
        nacosConfig.addListener(NacosDataIdEnum.AI_AGENT_RETRY.dataId(), new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                log.info("[OkHttpConfig] 收到 ai-agent-retry.json 变更，重试参数下次调用自动生效");
            }
        });
    }

    private boolean poolParamChanged(OkHttpParam old, OkHttpParam neo) {
        return old.getMaxIdleConnections() != neo.getMaxIdleConnections()
                || old.getKeepAliveMinutes() != neo.getKeepAliveMinutes();
    }

    private void evict(OkHttpClient old) {
        if (old == null) return;
        try {
            old.connectionPool().evictAll();
        } catch (Exception e) {
            log.warn("[OkHttpConfig] 释放旧连接池失败, error={}", e.getMessage());
        }
    }

    // ==================== 构建 Client ====================

    /**
     * 构建基础 Client，只设置连接池参数和基础超时（作为默认值）。
     * 超时参数会在 getClient() / getLlmClient() 里通过 newBuilder() 覆盖。
     */
    private OkHttpClient buildBaseClient(OkHttpParam p) {
        ConnectionPool pool = new ConnectionPool(p.getMaxIdleConnections(), p.getKeepAliveMinutes(), TimeUnit.MINUTES);
        return new OkHttpClient.Builder()
                .connectionPool(pool)
                .connectTimeout(p.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(p.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(p.getWriteTimeoutSeconds(), TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .protocols(Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build();
    }

    // ==================== 读取参数 ====================

    /** 读取全局 OkHttp 连接参数（{@code "okhttp"} 块），用于构建基础 Client 和普通请求 */
    private OkHttpParam readOkHttpParam() {
        OkHttpParam param = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_HTTP, OKHTTP_KEY, OkHttpParam.class);
        if (param == null) {
            log.debug("[OkHttpConfig] Nacos 未配置 okhttp 参数，使用默认值");
            return DEFAULT_OKHTTP_PARAM;
        }
        return param;
    }

    /** 读取 LLM 全局超时参数（{@code "llm"} 块），fallback 到 {@code "okhttp"} 块 */
    private OkHttpParam readLlmOkHttpParam() {
        OkHttpParam param = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_HTTP, LLM_KEY, OkHttpParam.class);
        if (param == null) {
            log.debug("[OkHttpConfig] Nacos 未配置 llm 超时参数，fallback 到 okhttp 全局参数");
            return currentParamRef.get(); // 直接用已缓存的 okhttp 参数，避免重复读取
        }
        return param;
    }
}

