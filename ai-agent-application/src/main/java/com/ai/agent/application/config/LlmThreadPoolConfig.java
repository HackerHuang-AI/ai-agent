package com.ai.agent.application.config;

import com.ai.agent.application.bo.ThreadPoolParam;
import com.ai.agent.infrastructure.config.NacosConfig;
import com.ai.agent.infrastructure.config.NacosDataId;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @Description: LLM 流式调用线程池统一配置。
 *
 * <p>设计要点：
 * <ul>
 *   <li>两个业务线程池隔离：doubao-stream-pool / deepseek-stream-pool，防止慢任务拖垮快任务</li>
 *   <li>启动时从 Nacos {@code ai-agent-thread-pool.json} 读取参数，读不到则使用默认值</li>
 *   <li>Nacos 配置变更时，直接在 listener 回调里调 {@link NacosConfigUtil#getObject} 读最新值，热更新线程池参数，无需重启</li>
 *   <li>队列使用内部类 {@link ResizableLinkedBlockingQueue}，支持运行时动态修改容量</li>
 *   <li>拒绝策略采用 CallerRunsPolicy：队列满时由调用方线程执行，起到背压效果，不丢任务</li>
 * </ul>
 *
 * <p>Nacos 配置示例（ai-agent-thread-pool.json）：
 * <pre>{@code
 * {
 *   "doubao-stream-pool":   { "corePoolSize": 10, "maxPoolSize": 50, "queueCapacity": 100, "keepAliveSeconds": 60 },
 *   "deepseek-stream-pool": { "corePoolSize": 5,  "maxPoolSize": 30, "queueCapacity": 50,  "keepAliveSeconds": 60 }
 * }
 * }</pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.config
 * @ClassName: LlmThreadPoolConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/6/26
 * @Version: 1.0
 */
@Slf4j
@Configuration
public class LlmThreadPoolConfig {

    // ==================== 线程池 key，与 Nacos JSON 中的 key 对应 ====================
    private static final String DOUBAO_POOL_KEY   = "doubao-stream-pool";
    private static final String DEEPSEEK_POOL_KEY = "deepseek-stream-pool";

    // ==================== 默认值（Nacos 未配置时兜底）====================
    private static final int DOUBAO_DEFAULT_CORE      = 10;
    private static final int DOUBAO_DEFAULT_MAX       = 50;
    private static final int DOUBAO_DEFAULT_QUEUE     = 100;
    private static final int DOUBAO_DEFAULT_KEEPALIVE = 60;

    private static final int DEEPSEEK_DEFAULT_CORE      = 5;
    private static final int DEEPSEEK_DEFAULT_MAX       = 30;
    private static final int DEEPSEEK_DEFAULT_QUEUE     = 50;
    private static final int DEEPSEEK_DEFAULT_KEEPALIVE = 60;

    @Autowired
    private NacosConfig nacosConfig;

    // ==================== 线程池实例（热更新时直接操作这两个）====================
    private ThreadPoolExecutor doubaoExecutor;
    private ThreadPoolExecutor deepseekExecutor;

    // ==================== Bean 定义 ====================

    @Bean("doubaoStreamExecutor")
    public ThreadPoolExecutor doubaoStreamExecutor() {
        ThreadPoolParam param = readParam(DOUBAO_POOL_KEY,
                DOUBAO_DEFAULT_CORE, DOUBAO_DEFAULT_MAX, DOUBAO_DEFAULT_QUEUE, DOUBAO_DEFAULT_KEEPALIVE);
        doubaoExecutor = buildExecutor(param, DOUBAO_POOL_KEY);
        log.info("[LlmThreadPool] {} 初始化完成, param={}", DOUBAO_POOL_KEY, param);
        return doubaoExecutor;
    }

    @Bean("deepseekStreamExecutor")
    public ThreadPoolExecutor deepseekStreamExecutor() {
        ThreadPoolParam param = readParam(DEEPSEEK_POOL_KEY,
                DEEPSEEK_DEFAULT_CORE, DEEPSEEK_DEFAULT_MAX, DEEPSEEK_DEFAULT_QUEUE, DEEPSEEK_DEFAULT_KEEPALIVE);
        deepseekExecutor = buildExecutor(param, DEEPSEEK_POOL_KEY);
        log.info("[LlmThreadPool] {} 初始化完成, param={}", DEEPSEEK_POOL_KEY, param);
        return deepseekExecutor;
    }

    // ==================== Nacos 热更新 ====================

    /**
     * Bean 初始化完成后注册 Nacos listener。
     * listener 回调时缓存已更新，直接调 NacosConfigUtil 读最新值，更新线程池参数。
     */
    @PostConstruct
    public void registerNacosListener() {
        nacosConfig.addListener(NacosDataId.AI_AGENT_THREAD_POOL.dataId(), new Listener() {
            @Override
            public Executor getExecutor() {
                return null;
            }

            @Override
            public void receiveConfigInfo(String configInfo) {
                log.info("[LlmThreadPool] 收到 Nacos 配置变更，开始热更新");
                hotUpdate(doubaoExecutor,   DOUBAO_POOL_KEY,
                        DOUBAO_DEFAULT_CORE, DOUBAO_DEFAULT_MAX, DOUBAO_DEFAULT_QUEUE, DOUBAO_DEFAULT_KEEPALIVE);
                hotUpdate(deepseekExecutor, DEEPSEEK_POOL_KEY,
                        DEEPSEEK_DEFAULT_CORE, DEEPSEEK_DEFAULT_MAX, DEEPSEEK_DEFAULT_QUEUE, DEEPSEEK_DEFAULT_KEEPALIVE);
            }
        });
    }

    private void hotUpdate(ThreadPoolExecutor executor, String poolKey,
                           int defaultCore, int defaultMax, int defaultQueue, int defaultKeepAlive) {
        if (executor == null) return;
        ThreadPoolParam newParam = readParam(poolKey, defaultCore, defaultMax, defaultQueue, defaultKeepAlive);

        int oldCore  = executor.getCorePoolSize();
        int oldMax   = executor.getMaximumPoolSize();
        int oldQueue = ((ResizableLinkedBlockingQueue<?>) executor.getQueue()).getCapacity();

        // 先调 max 再调 core（避免 core > max 报错）；缩容时先调 core 再调 max
        if (newParam.getMaxPoolSize() >= executor.getCorePoolSize()) {
            executor.setMaximumPoolSize(newParam.getMaxPoolSize());
            executor.setCorePoolSize(newParam.getCorePoolSize());
        } else {
            executor.setCorePoolSize(newParam.getCorePoolSize());
            executor.setMaximumPoolSize(newParam.getMaxPoolSize());
        }
        executor.setKeepAliveTime(newParam.getKeepAliveSeconds(), TimeUnit.SECONDS);
        ((ResizableLinkedBlockingQueue<?>) executor.getQueue()).setCapacity(newParam.getQueueCapacity());

        log.info("[LlmThreadPool] {} 热更新完成: core {} → {}, max {} → {}, queue {} → {}",
                poolKey, oldCore, newParam.getCorePoolSize(),
                oldMax, newParam.getMaxPoolSize(),
                oldQueue, newParam.getQueueCapacity());
    }

    // ==================== 优雅关闭 ====================

    @PreDestroy
    public void shutdown() {
        shutdownExecutor(doubaoExecutor,   DOUBAO_POOL_KEY);
        shutdownExecutor(deepseekExecutor, DEEPSEEK_POOL_KEY);
    }

    private void shutdownExecutor(ThreadPoolExecutor executor, String name) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                log.warn("[LlmThreadPool] {} 等待超时，强制终止", name);
            } else {
                log.info("[LlmThreadPool] {} 已优雅关闭", name);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 工具方法 ====================

    private ThreadPoolParam readParam(String poolKey, int defaultCore, int defaultMax,
                                      int defaultQueue, int defaultKeepAlive) {
        ThreadPoolParam param = NacosConfigUtil.getObject(
                NacosDataId.AI_AGENT_THREAD_POOL, poolKey, ThreadPoolParam.class);
        if (param == null) {
            log.warn("[LlmThreadPool] Nacos 未配置 {}，使用默认参数 core={} max={} queue={} keepAlive={}s",
                    poolKey, defaultCore, defaultMax, defaultQueue, defaultKeepAlive);
            param = new ThreadPoolParam();
            param.setCorePoolSize(defaultCore);
            param.setMaxPoolSize(defaultMax);
            param.setQueueCapacity(defaultQueue);
            param.setKeepAliveSeconds(defaultKeepAlive);
        }
        return param;
    }

    private ThreadPoolExecutor buildExecutor(ThreadPoolParam param, String poolName) {
        ResizableLinkedBlockingQueue<Runnable> queue =
                new ResizableLinkedBlockingQueue<>(param.getQueueCapacity());
        return new ThreadPoolExecutor(
                param.getCorePoolSize(),
                param.getMaxPoolSize(),
                param.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                queue,
                new NamedThreadFactory(poolName),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    // ==================== 内部类：可变容量阻塞队列 ====================

    /**
     * 支持运行时动态修改容量的阻塞队列。
     * 不依赖反射，自维护 volatile capacity，覆写 offer() 做容量判断，兼容 Java 9+ 模块系统。
     * LinkedBlockingQueue 传入 Integer.MAX_VALUE 使其内部不限制，由本类的 offer() 负责限流。
     */
    private static class ResizableLinkedBlockingQueue<E> extends LinkedBlockingQueue<E> {

        private volatile int capacity;

        ResizableLinkedBlockingQueue(int capacity) {
            super(Integer.MAX_VALUE);
            this.capacity = capacity;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int newCapacity) {
            if (newCapacity <= 0) {
                throw new IllegalArgumentException("队列容量必须大于 0，实际值: " + newCapacity);
            }
            int old = this.capacity;
            this.capacity = newCapacity;
            log.info("[ResizableQueue] 队列容量变更: {} → {}", old, newCapacity);
        }

        @Override
        public boolean offer(E e) {
            return size() < capacity && super.offer(e);
        }
    }

    // ==================== 内部类：命名线程工厂 ====================

    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final AtomicInteger threadNumber = new AtomicInteger(1);

        NamedThreadFactory(String poolName) {
            this.namePrefix = poolName + "-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }
}

