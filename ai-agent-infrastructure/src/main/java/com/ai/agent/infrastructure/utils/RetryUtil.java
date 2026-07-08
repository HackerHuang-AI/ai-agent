package com.ai.agent.infrastructure.utils;

import com.ai.agent.infrastructure.config.param.RetryParam;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;

/**
 * @Description: 重试工具类，采用指数退避策略（参考 open-code-review 设计 + jitter 优化）。
 *
 * <p>等待时间公式：
 * <pre>
 *   wait = min(intervalMs * multiplier^(attempt-1), maxWaitMs)
 *   实际等待 = wait + random(0, wait * 0.1)   ← 加 10% 随机抖动，避免雪崩
 * </pre>
 *
 * <p>触发重试的条件：
 * <ul>
 *   <li>callable 抛出异常</li>
 *   <li>callable 返回 null</li>
 * </ul>
 *
 * <p>重试参数通过 {@link RetryParam} 传入，调用方从 OkHttpConfig 实时读取 Nacos 值，保证热更新生效。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: RetryUtil
 * @Author: HUANGcong
 * @Date: Created in 2026/5/13
 * @Version: 2.0
 */
@Slf4j
public class RetryUtil {

    private RetryUtil() {}

    /**
     * 使用 {@link RetryParam} 执行重试（推荐）。
     * 参数从 OkHttpConfig 实时读取，热更新立即生效。
     */
    public static <T> T retry(Callable<T> callable, RetryParam param) {
        return retry(callable, param.getMaxRetries(), param.getIntervalMs(),
                param.getBackoffMultiplier(), param.getMaxWaitMs());
    }

    /**
     * 指数退避重试，兼容旧调用方式。
     * 如无特殊需求，优先使用 {@link #retry(Callable, RetryParam)}。
     *
     * @param callable        被执行的任务
     * @param maxRetries      最大重试次数（不含首次调用），共最多 maxRetries+1 次执行
     * @param intervalMs      基础等待时间（毫秒）
     * @param multiplier      指数退避乘数，第 n 次重试等待 = intervalMs * multiplier^(n-1)
     * @param maxWaitMs       单次等待上限（毫秒）
     */
    public static <T> T retry(Callable<T> callable, int maxRetries, long intervalMs,
                               double multiplier, long maxWaitMs) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                T result = callable.call();
                if (result != null) {
                    if (attempt > 0) {
                        log.info("[RetryUtil] 重试成功，第 {} 次重试", attempt);
                    }
                    return result;
                }
                log.warn("[RetryUtil] 执行返回为空，第 {}/{} 次", attempt + 1, maxRetries + 1);
            } catch (Exception e) {
                lastException = e;
                log.warn("[RetryUtil] 执行异常，第 {}/{} 次，异常：{}", attempt + 1, maxRetries + 1, e.getMessage());
            }

            if (attempt < maxRetries) {
                long wait = computeWait(attempt, intervalMs, multiplier, maxWaitMs);
                log.info("[RetryUtil] 等待 {}ms 后发起第 {} 次重试", wait, attempt + 1);
                sleep(wait);
            }
        }

        log.error("[RetryUtil] 全部重试失败，共 {} 次，基础间隔 {}ms，乘数 {}",
                maxRetries + 1, intervalMs, multiplier, lastException);
        return null;
    }

    /**
     * 计算第 attempt 次重试前的等待时间（含 jitter）。
     * wait = min(intervalMs * multiplier^attempt, maxWaitMs)
     * 实际等待在 [wait, wait * 1.1) 之间随机，避免多实例同时重试造成雪崩。
     */
    private static long computeWait(int attempt, long intervalMs, double multiplier, long maxWaitMs) {
        double base = intervalMs * Math.pow(multiplier, attempt);
        long wait = Math.min((long) base, maxWaitMs);
        // 加 0~10% 随机抖动
        long jitter = (long) (wait * Math.random() * 0.1);
        return wait + jitter;
    }


    /**
     * 等待指定时间
     * @param ms 等待时间（毫秒）
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[RetryUtil] 重试等待被中断，终止后续重试");
        }
    }
}

