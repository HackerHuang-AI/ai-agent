package com.ai.agent.infrastructure.config;

import com.ai.agent.infrastructure.config.param.OkHttpParam;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.enums.OkHttpConfigEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;
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
 *   <li>{@link #getLlmClient(String)} 每次调用时在基础 Client 上 newBuilder()
 *       叠加最新超时参数返回，连接池始终只有一个，无切换抖动</li>
 *   <li>OkHttpClient.newBuilder() 复用父 Client 的连接池，不会创建新连接池</li>
 *   <li>OkHttp 连接参数来自 {@code ai-agent-http.json}；重试参数独立由 {@link RetryConfig} 管理</li>
 *   <li>平台专属超时支持按需读取，热更新后下一次请求即生效</li>
 * </ul>
 *
 * <p>Nacos 配置示例（ai-agent-http.json）—— 只放网络连接参数：
 * <pre>{@code
 * {
 *   "okhttp":   { "connectTimeoutSeconds": 5, "readTimeoutSeconds": 15, "writeTimeoutSeconds": 10, "maxIdleConnections": 50, "keepAliveMinutes": 5 },
 *   "default":  { "connectTimeoutSeconds": 10, "readTimeoutSeconds": 120, "writeTimeoutSeconds": 30, "maxIdleConnections": 50, "keepAliveMinutes": 5 },
 *   "doubao":   { "connectTimeoutSeconds": 10, "readTimeoutSeconds": 180, "writeTimeoutSeconds": 30, "maxIdleConnections": 50, "keepAliveMinutes": 5 },
 *   "deepseek": { "connectTimeoutSeconds": 15, "readTimeoutSeconds": 60,  "writeTimeoutSeconds": 30, "maxIdleConnections": 50, "keepAliveMinutes": 5 }
 * }
 * }</pre>
 * <p>查找语义（与 RetryConfig 对齐）：平台专属块 → {@code "default"} 全局块 → {@code "okhttp"} 块（代码默认值），
 * 每个平台需全量配置自己的超时参数，无字段级继承。
 *
 * <p>Nacos 配置示例（ai-agent-retry.json）—— 只放重试策略：
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
 * @ClassName: OkHttpConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/7/8
 * @Version: 2.0
 */
@Slf4j
@Component
public class OkHttpConfig {

    // ==================== Nacos key，与 ai-agent-http.json 中的 key 对应 ====================
    private static final String OKHTTP_KEY = "okhttp";

    // ==================== 默认参数（Nacos 未配置时兜底）====================
    private static final OkHttpParam DEFAULT_OKHTTP_PARAM = new OkHttpParam();

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
     * 获取指定业务场景的 OkHttpClient，支持按场景独立配置超时参数。
     *
     * <p>查找链路（均读自 {@code ai-agent-http.json}）：
     * <ol>
     *   <li>有平台专属块（如 {@code "doubao"}）→ 直接使用，全量自定义</li>
     *   <li>无专属块 → fallback 到 {@code "default"} 全局块</li>
     *   <li>{@code "default"} 也未配置 → 使用 {@code "okhttp"} 块（代码默认值兜底）</li>
     * </ol>
     * <p>与 RetryConfig 设计完全对齐：平台 → default → 代码默认值，无 llm 中间层。
     *
     * @param scope 业务场景标识（不区分大小写，如平台名 {@code "doubao"}；传 null 或空串走 default 兜底）
     */
    public OkHttpClient getLlmClient(String scope) {
        OkHttpConfigEnum def = OkHttpConfigEnum.of(scope);
        log.debug("[OkHttpConfig] scope={} → nacosKey={}", scope, def.nacosKey);

        OkHttpParam p = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_HTTP, def.nacosKey, OkHttpParam.class);
        if (p == null && def != OkHttpConfigEnum.DEFAULT) {
            // 平台专属未配置，fallback 到 default 全局块
            p = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_HTTP, OkHttpConfigEnum.DEFAULT.nacosKey, OkHttpParam.class);
        }
        if (p == null) {
            // default 也未配置，fallback 到 okhttp 基础参数（代码默认值）
            p = currentParamRef.get();
        }

        log.debug("[OkHttpConfig] scope={} 最终参数: connect={}s, read={}s, write={}s, proxy={}",
                scope, p.getConnectTimeoutSeconds(), p.getReadTimeoutSeconds(), p.getWriteTimeoutSeconds(),
                p.getProxy() != null ? p.getProxy().getHost() + ":" + p.getProxy().getPort() : "none");
        OkHttpClient.Builder builder = baseClientRef.get().newBuilder()
                .connectTimeout(p.getConnectTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(p.getReadTimeoutSeconds(), TimeUnit.SECONDS)
                .writeTimeout(p.getWriteTimeoutSeconds(), TimeUnit.SECONDS);
        applyProxy(builder, p.getProxy());
        return builder.build();
    }

    /**
     * 按需注入代理配置。proxy 为 null 时不做任何设置（不走代理）。
     * 支持 HTTP / SOCKS 两种类型，以及可选的用户名/密码认证。
     */
    private void applyProxy(OkHttpClient.Builder builder, OkHttpParam.ProxyParam proxy) {
        if (proxy == null || proxy.getHost() == null || proxy.getHost().isBlank()) {
            return;
        }
        Proxy.Type type = "SOCKS".equalsIgnoreCase(proxy.getType()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        builder.proxy(new Proxy(type, new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
            String credential = Credentials.basic(proxy.getUsername(), proxy.getPassword());
            Authenticator proxyAuthenticator = (route, response) ->
                    response.request().newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build();
            builder.proxyAuthenticator(proxyAuthenticator);
        }
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

}

