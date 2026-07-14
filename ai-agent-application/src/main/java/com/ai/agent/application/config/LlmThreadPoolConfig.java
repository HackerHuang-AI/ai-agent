package com.ai.agent.application.config;

import com.ai.agent.application.config.param.ThreadPoolParam;
import com.ai.agent.infrastructure.config.NacosConfig;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @Description: LLM 流式调用线程池统一配置。
 *
 * <p>设计要点：
 * <ul>
 *   <li>数据驱动：平台配置收拢到内部枚举 {@link PoolDef}，新增平台只需加一行 enum + 一行 @Bean</li>
 *   <li>启动时从 Nacos {@code ai-agent-thread-pool.json} 读取参数，读不到则使用 PoolDef 中的默认值</li>
 *   <li>Nacos 配置变更时遍历枚举热更新所有线程池参数，无需重启</li>
 *   <li>队列使用内部类 {@link ResizableLinkedBlockingQueue}，支持运行时动态修改容量</li>
 *   <li>拒绝策略：队列满时记录错误日志、累计计数并抛出 RejectedExecutionException，由调用方感知压力后返回 [ERROR]，避免阻塞 Tomcat 线程</li>
 *   <li>定时快照：每 60 秒打印各线程池 active/queue/completed/rejected 指标，用于问题排查</li>
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
 * @Version: 2.0
 */
@Slf4j
@Configuration
public class LlmThreadPoolConfig {

    // ==================== 平台线程池定义表 ====================
    // 每行：nacosKey, beanName, defaultCore, defaultMax, defaultQueue, defaultKeepAliveSeconds
    // Nacos 有配置时用 Nacos 值，无配置时用此处默认值兜底

    enum PoolDef {
        DOUBAO          ("doubao-stream-pool",             10, 50, 100, 60),
        DEEPSEEK        ("deepseek-stream-pool",            5, 30,  50, 60),
        QWEN            ("qwen-stream-pool",                5, 30,  50, 60),
        ZHIPU           ("zhipu-stream-pool",               5, 30,  50, 60),
        MOONSHOT        ("moonshot-stream-pool",            5, 30,  50, 60),
        OPENAI          ("openai-stream-pool",              5, 30,  50, 60),
        ANTHROPIC       ("anthropic-stream-pool",           5, 30,  50, 60),
        GEMINI          ("gemini-stream-pool",              5, 30,  50, 60),
        MIMO            ("mimo-stream-pool",                5, 30,  50, 60),
        MINIMAX         ("minimax-stream-pool",             5, 30,  50, 60),
        OLLAMA          ("ollama-stream-pool",              5, 30,  50, 60),
        QIANFAN         ("qianfan-stream-pool",             5, 30,  50, 60),
        TOKENHUB        ("tokenhub-stream-pool",            5, 30,  50, 60),
        DS_TOKENPLAN    ("dashscope-tokenplan-stream-pool", 5, 30,  50, 60),
        ;

        final String nacosKey;
        final int defaultCore;
        final int defaultMax;
        final int defaultQueue;
        final int defaultKeepAlive;

        PoolDef(String nacosKey, int defaultCore, int defaultMax, int defaultQueue, int defaultKeepAlive) {
            this.nacosKey         = nacosKey;
            this.defaultCore      = defaultCore;
            this.defaultMax       = defaultMax;
            this.defaultQueue     = defaultQueue;
            this.defaultKeepAlive = defaultKeepAlive;
        }
    }

    // ==================== 线程池实例表（枚举驱动，替代 15 个独立字段）====================

    private final Map<PoolDef, ThreadPoolExecutor> executors = new EnumMap<>(PoolDef.class);

    /** 各线程池累计拒绝任务计数，key 与 executors 一一对应，用于定时快照日志 */
    private final Map<PoolDef, AtomicLong> rejectedCounts = new EnumMap<>(PoolDef.class);

    /** 定时快照调度器，字段持有以便 @PreDestroy 优先停止，避免关闭期间仍触发 logStats */
    private ScheduledExecutorService statsScheduler;

    @Autowired
    private NacosConfig nacosConfig;

    // ==================== Bean 定义（每个 @Bean 只有一行逻辑，职责纯粹）====================

    @Bean("doubaoStreamExecutor")
    public ThreadPoolExecutor doubaoStreamExecutor()         { return createAndRegister(PoolDef.DOUBAO); }

    @Bean("deepseekStreamExecutor")
    public ThreadPoolExecutor deepseekStreamExecutor()       { return createAndRegister(PoolDef.DEEPSEEK); }

    @Bean("qwenStreamExecutor")
    public ThreadPoolExecutor qwenStreamExecutor()           { return createAndRegister(PoolDef.QWEN); }

    @Bean("zhipuStreamExecutor")
    public ThreadPoolExecutor zhipuStreamExecutor()          { return createAndRegister(PoolDef.ZHIPU); }

    @Bean("moonshotStreamExecutor")
    public ThreadPoolExecutor moonshotStreamExecutor()       { return createAndRegister(PoolDef.MOONSHOT); }

    @Bean("openaiStreamExecutor")
    public ThreadPoolExecutor openaiStreamExecutor()         { return createAndRegister(PoolDef.OPENAI); }

    @Bean("anthropicStreamExecutor")
    public ThreadPoolExecutor anthropicStreamExecutor()      { return createAndRegister(PoolDef.ANTHROPIC); }

    @Bean("geminiStreamExecutor")
    public ThreadPoolExecutor geminiStreamExecutor()         { return createAndRegister(PoolDef.GEMINI); }

    @Bean("mimoStreamExecutor")
    public ThreadPoolExecutor mimoStreamExecutor()           { return createAndRegister(PoolDef.MIMO); }

    @Bean("minimaxStreamExecutor")
    public ThreadPoolExecutor minimaxStreamExecutor()        { return createAndRegister(PoolDef.MINIMAX); }

    @Bean("ollamaStreamExecutor")
    public ThreadPoolExecutor ollamaStreamExecutor()         { return createAndRegister(PoolDef.OLLAMA); }

    @Bean("qianfanStreamExecutor")
    public ThreadPoolExecutor qianfanStreamExecutor()        { return createAndRegister(PoolDef.QIANFAN); }

    @Bean("tokenhubStreamExecutor")
    public ThreadPoolExecutor tokenhubStreamExecutor()       { return createAndRegister(PoolDef.TOKENHUB); }


    @Bean("dsTokenPlanStreamExecutor")
    public ThreadPoolExecutor dsTokenPlanStreamExecutor()    { return createAndRegister(PoolDef.DS_TOKENPLAN); }

    // ==================== Nacos 热更新 + 定时状态快照 ====================

    @PostConstruct
    public void registerNacosListenerAndStartStats() {
        nacosConfig.addListener(NacosDataIdEnum.AI_AGENT_THREAD_POOL.dataId(), new Listener() {
            @Override
            public Executor getExecutor() { return null; }

            @Override
            public void receiveConfigInfo(String configInfo) {
                log.info("[LlmThreadPool] 收到 Nacos 配置变更，开始热更新");
                executors.forEach((def, executor) -> hotUpdate(executor, def));
            }
        });

        // 每 60 秒打印一次各线程池状态快照，用于排查线程池积压/拒绝问题
        statsScheduler = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "llm-pool-stats"); t.setDaemon(true); return t; });
        statsScheduler.scheduleAtFixedRate(this::logStats, 60, 60, TimeUnit.SECONDS);
    }

    private void logStats() {
        log.info("[LlmThreadPool-Stats] view");
        executors.forEach((def, executor) -> {
            AtomicLong rejected = rejectedCounts.get(def); // 必然存在，createAndRegister 已 put
            log.info("[LlmThreadPool-Stats] {} active={}/{} queue={}/{} completed={} rejected={}",
                    def.nacosKey,
                    executor.getActiveCount(), executor.getMaximumPoolSize(),
                    executor.getQueue().size(),
                    ((ResizableLinkedBlockingQueue<?>) executor.getQueue()).getCapacity(),
                    executor.getCompletedTaskCount(),
                    rejected != null ? rejected.get() : 0);
        });
    }

    // ==================== 优雅关闭 ====================

    @PreDestroy
    public void shutdown() {
        // 先停快照调度器，避免 Spring 关闭期间 logStats 访问正在被清理的 executors
        if (statsScheduler != null) {
            statsScheduler.shutdownNow();
        }
        executors.forEach((def, executor) -> shutdownExecutor(executor, def.nacosKey));
    }

    // ==================== 私有方法 ====================

    private ThreadPoolExecutor createAndRegister(PoolDef def) {
        AtomicLong rejected = new AtomicLong(0);
        rejectedCounts.put(def, rejected);
        ThreadPoolParam param = readParam(def);
        ThreadPoolExecutor executor = buildExecutor(param, def.nacosKey, rejected);
        executors.put(def, executor);
        log.info("[LlmThreadPool] {} 初始化完成, param={}", def.nacosKey, param);
        return executor;
    }

    private void hotUpdate(ThreadPoolExecutor executor, PoolDef def) {
        if (executor == null) return;
        ThreadPoolParam newParam = readParam(def);

        int oldCore  = executor.getCorePoolSize();
        int oldMax   = executor.getMaximumPoolSize();
        int oldQueue = ((ResizableLinkedBlockingQueue<?>) executor.getQueue()).getCapacity();

        // 参数无变化，跳过，避免无效更新和冗余日志
        if (oldCore == newParam.getCorePoolSize()
                && oldMax == newParam.getMaxPoolSize()
                && oldQueue == newParam.getQueueCapacity()
                && executor.getKeepAliveTime(TimeUnit.SECONDS) == newParam.getKeepAliveSeconds()) {
            return;
        }

        // 扩容：新 core 超过当前 max 时，必须先扩 max，再改 core，否则 core > max 抛异常
        // 缩容：新 max 小于当前 core 时，必须先缩 core，再缩 max，否则 max < core 抛异常
        if (newParam.getCorePoolSize() > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(newParam.getMaxPoolSize());
            executor.setCorePoolSize(newParam.getCorePoolSize());
        } else {
            executor.setCorePoolSize(newParam.getCorePoolSize());
            executor.setMaximumPoolSize(newParam.getMaxPoolSize());
        }
        executor.setKeepAliveTime(newParam.getKeepAliveSeconds(), TimeUnit.SECONDS);
        ((ResizableLinkedBlockingQueue<?>) executor.getQueue()).setCapacity(newParam.getQueueCapacity());

        log.info("[LlmThreadPool] {} 热更新完成: core {} → {}, max {} → {}, queue {} → {}",
                def.nacosKey, oldCore, newParam.getCorePoolSize(),
                oldMax, newParam.getMaxPoolSize(),
                oldQueue, newParam.getQueueCapacity());
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

    private ThreadPoolParam readParam(PoolDef def) {
        ThreadPoolParam param = NacosConfigUtil.getObject(
                NacosDataIdEnum.AI_AGENT_THREAD_POOL, def.nacosKey, ThreadPoolParam.class);
        if (param == null) {
            log.warn("[LlmThreadPool] Nacos 未配置 {}，使用默认参数 core={} max={} queue={} keepAlive={}s",
                    def.nacosKey, def.defaultCore, def.defaultMax, def.defaultQueue, def.defaultKeepAlive);
            param = new ThreadPoolParam();
            param.setCorePoolSize(def.defaultCore);
            param.setMaxPoolSize(def.defaultMax);
            param.setQueueCapacity(def.defaultQueue);
            param.setKeepAliveSeconds(def.defaultKeepAlive);
        }
        return param;
    }

    private ThreadPoolExecutor buildExecutor(ThreadPoolParam param, String poolName, AtomicLong rejectedCount) {
        ResizableLinkedBlockingQueue<Runnable> queue =
                new ResizableLinkedBlockingQueue<>(param.getQueueCapacity(), poolName);
        return new ThreadPoolExecutor(
                param.getCorePoolSize(),
                param.getMaxPoolSize(),
                param.getKeepAliveSeconds(),
                TimeUnit.SECONDS,
                queue,
                new NamedThreadFactory(poolName),
                // 拒绝策略：记录日志 + 计数 + 抛出异常，让调用方感知服务压力
                // 不使用 CallerRunsPolicy，避免 LLM 长耗时任务阻塞 Tomcat 线程导致雪崩
                (r, executor) -> {
                    rejectedCount.incrementAndGet();
                    log.error("[LlmThreadPool] {} 线程池已满，拒绝任务: active={}/{}, queue={}/{}",
                            poolName,
                            executor.getActiveCount(), executor.getMaximumPoolSize(),
                            executor.getQueue().size(),
                            ((ResizableLinkedBlockingQueue<?>) executor.getQueue()).getCapacity());
                    throw new RejectedExecutionException("LLM 线程池已满: " + poolName);
                }
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
        /** 所属线程池名称，用于日志区分；由外部在构造时传入 */
        private final String poolName;

        ResizableLinkedBlockingQueue(int capacity, String poolName) {
            super(Integer.MAX_VALUE);
            this.capacity = capacity;
            this.poolName = poolName;
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
            log.info("[ResizableQueue] {} 队列容量变更: {} → {}", poolName, old, newCapacity);
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

