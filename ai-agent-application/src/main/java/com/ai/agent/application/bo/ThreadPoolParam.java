package com.ai.agent.application.bo;

import lombok.Data;

/**
 * @Description: 线程池参数配置实体，对应 Nacos ai-agent-thread-pool.json 中每个线程池的参数块。
 *               字段缺失时由 LlmThreadPoolConfig 兜底使用默认值，无需在此设置默认值。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.bo
 * @ClassName: ThreadPoolParam
 * @Author: HUANGcong
 * @Date: Created in 2026/6/26
 * @Version: 1.0
 */
@Data
public class ThreadPoolParam {

    /** 核心线程数，常驻线程，不会被回收 */
    private int corePoolSize;

    /** 最大线程数，超过此数量的请求进队列等待 */
    private int maxPoolSize;

    /** 等待队列容量，队列满后新任务触发拒绝策略 */
    private int queueCapacity;

    /** 空闲线程存活时间（秒），超过核心线程数的线程在此时间后回收 */
    private int keepAliveSeconds;
}

