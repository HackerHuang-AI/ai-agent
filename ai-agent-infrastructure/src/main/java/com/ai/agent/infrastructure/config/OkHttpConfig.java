package com.ai.agent.infrastructure.config;

import com.ai.agent.infrastructure.config.param.OkHttpParam;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.enums.OkHttpConfigEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>平台专属超时参数缓存在 {@link #llmParamCache} 中，避免每次请求触发 JSON 反序列化；
 *       Nacos 回调时 clear，下次请求懒加载最新值，保证热更新及时生效</li>
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

    /**
     * 哨兵对象：标记某个 nacosKey 在 Nacos 中确实不存在（getObject 返回 null）。
     * ConcurrentHashMap 不允许存 null 值，用哨兵替代，使 computeIfAbsent 能缓存
     * "不存在"这一结果，避免每次请求重复触发反序列化。Nacos 变更时随 llmParamCache 一并 clear。
     */
    private static final OkHttpParam ABSENT_PARAM = new OkHttpParam();

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

    /**
     * LLM 平台参数缓存（key=nacosKey，value=已解析的 OkHttpParam）。
     * 避免每次 getLlmClient() 都触发 JSON 反序列化；Nacos 配置变更时 clear，
     * 下次请求通过 computeIfAbsent 懒加载最新值。
     * 使用 ConcurrentHashMap 保证读写并发安全，值为不可变对象，无额外锁开销。
     */
    private final ConcurrentHashMap<String, OkHttpParam> llmParamCache = new ConcurrentHashMap<>();

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
     * 获取指定业务场景的 OkHttpClient，支持按场景独立配置超时与代理。
     *
     * <p>查找链路（均读自 {@code ai-agent-http.json}）：
     * <ol>
     *   <li>有平台专属块（如 {@code "gemini"}）→ 直接使用，全量自定义（含代理）</li>
     *   <li>无专属块 → fallback 到 {@code "default"} 全局块（通常不含代理）</li>
     *   <li>{@code "default"} 也未配置 → 使用代码默认值兜底（无代理）</li>
     * </ol>
     *
     * <p>代理配置说明：
     * <ul>
     *   <li>在平台专属块中配置 {@code "proxy"} 字段即可启用，不配置则直连</li>
     *   <li>fallback 到 default 块时，若 default 块无代理配置，则该平台不走代理</li>
     *   <li>每次调用实时读取，Nacos 热更新后下一次请求自动生效，无需重启</li>
     * </ul>
     *
     * @param scope 业务场景标识（不区分大小写，如平台名 {@code "gemini"}；传 null 或空串走 default 兜底）
     */
    public OkHttpClient getLlmClient(String scope) {
        OkHttpConfigEnum def = OkHttpConfigEnum.of(scope);
        log.debug("[OkHttpConfig] scope={} → nacosKey={}", scope, def.nacosKey);

        // 优先读缓存；缓存 miss 时才反序列化 Nacos JSON，并写入缓存供后续请求复用。
        // ConcurrentHashMap 不允许存 null，用 ABSENT_PARAM 哨兵表示"Nacos 中无此 key"，
        // 使"不存在"结果也能被缓存，避免每次都重复触发反序列化。
        // Nacos 变更时 llmParamCache 会被 clear，下次请求触发 computeIfAbsent 重建。
        OkHttpParam p = llmParamCache.computeIfAbsent(def.nacosKey, key -> {
            OkHttpParam result = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_HTTP, key, OkHttpParam.class);
            return result != null ? result : ABSENT_PARAM;  // null 用哨兵替代，使缓存生效
        });
        if (p == ABSENT_PARAM && def != OkHttpConfigEnum.DEFAULT) {
            // 平台专属未配置（哨兵），fallback 到 default 全局块（结果也缓存）
            p = llmParamCache.computeIfAbsent(OkHttpConfigEnum.DEFAULT.nacosKey, key -> {
                OkHttpParam result = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_HTTP, key, OkHttpParam.class);
                return result != null ? result : ABSENT_PARAM;
            });
        }
        if (p == ABSENT_PARAM) {
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
     * 按需注入代理配置。proxy 为 null 或 host 为空时不做任何设置（直连，不走代理）。
     * 支持 HTTP / SOCKS 两种类型，以及可选的用户名/密码认证。
     *
     * <p>代理配置在每次 {@link #getLlmClient(String)} 调用时动态注入，无需重建 baseClient，
     * Nacos 热更新后下一次请求即自动生效。
     */
    private void applyProxy(OkHttpClient.Builder builder, OkHttpParam.ProxyParam proxy) {
        if (proxy == null || proxy.getHost() == null || proxy.getHost().isBlank()) {
            return;
        }
        Proxy.Type type = "SOCKS".equalsIgnoreCase(proxy.getType()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        builder.proxy(new Proxy(type, new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
            String credential = Credentials.basic(proxy.getUsername(), proxy.getPassword());
            builder.proxyAuthenticator((route, response) -> {
                // 若上一次认证已带过凭证但服务器仍返回 407，说明凭证无效，返回 null 终止重试，避免无限循环
                if (response.request().header("Proxy-Authorization") != null) {
                    return null;
                }
                return response.request().newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build();
            });
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
                // 清除 LLM 平台参数缓存，下次 getLlmClient() 调用时重新从 Nacos 读取最新值
                llmParamCache.clear();
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

