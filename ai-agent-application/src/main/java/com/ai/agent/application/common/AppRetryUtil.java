package com.ai.agent.application.common;

import com.ai.agent.infrastructure.config.param.RetryParam;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.concurrent.Callable;

/**
 * @Description: 应用层重试工具类，在基础重试能力之上感知业务异常（BizException）。
 *
 * <p>与基础层 {@code RetryUtil} 的区别：
 * <ul>
 *   <li>{@code RetryUtil}：纯技术层，RuntimeException 一律直接抛出，不感知业务语义</li>
 *   <li>{@code AppRetryUtil}：应用层，直接 catch {@link BizException}，
 *       根据错误码决定重试还是直接抛出，零反射，无性能损耗</li>
 * </ul>
 *
 * <p>等待时间公式（与 RetryUtil 一致）：
 * <pre>
 *   wait = min(intervalMs * multiplier^(attempt-1), maxWaitMs)
 *   实际等待 = wait + random(0, wait * 0.1)   ← 加 10% 随机抖动，避免雪崩
 * </pre>
 *
 * <p>重试决策逻辑：
 * <ul>
 *   <li>{@link BizException} + errorCode.code 在 nonRetryableCodes 中 → 直接抛出，不重试</li>
 *   <li>{@link BizException} + errorCode.code 不在 nonRetryableCodes 中 → 继续重试</li>
 *   <li>其他 {@link Exception}（IO 异常、网络抖动等）→ 继续重试</li>
 *   <li>callable 返回 null → 继续重试</li>
 * </ul>
 *
 * <p>nonRetryableCodes 优先级（高 → 低）：
 * <ol>
 *   <li>调用方显式传入（不为 null 时优先使用）</li>
 *   <li>{@link RetryParam#getNonRetryableCodes()}（Nacos 配置）</li>
 *   <li>两者均为空 → 不限制，所有 BizException 均可重试</li>
 * </ol>
 *
 * <p>nonRetryableCodes 中存储 {@code ErrorCodeEnum.code} 字段值，如：
 * <pre>
 *   "2002009" → LLM_AUTH_FAILED（认证失败，重试无意义）
 *   "2001001" → PARAM_ILLEGAL（参数错误，重试无意义）
 *   "2002010" → LLM_INSUFFICIENT_BALANCE（余额不足，重试无意义）
 * </pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.common
 * @ClassName: AppRetryUtil
 * @Author: HUANGcong
 * @Date: Created in 2026/7/16
 * @Version: 1.0
 */
@Slf4j
public class AppRetryUtil {

    private AppRetryUtil() {}

    /**
     * 使用 {@link RetryParam} 执行重试，nonRetryableCodes 从 param 中读取（Nacos 配置）。
     */
    public static <T> T retry(Callable<T> callable, RetryParam param) {
        return retry(callable, param, null);
    }

    /**
     * 使用 {@link RetryParam} 执行重试，支持调用方显式传入 nonRetryableCodes。
     *
     * <p>nonRetryableCodes 不为 null 时优先使用，忽略 param 中的配置；
     * 为 null 时降级读取 {@link RetryParam#getNonRetryableCodes()}；
     * 两者均为空时不限制，所有 BizException 均可重试。
     *
     * @param callable          被执行的任务
     * @param param             重试参数（maxRetries/intervalMs/backoffMultiplier/maxWaitMs）
     * @param nonRetryableCodes 调用方显式指定的不可重试错误码集合（ErrorCodeEnum.code 值）；
     *                          传 null 则降级读 Nacos 配置
     */
    public static <T> T retry(Callable<T> callable, RetryParam param,
                               Collection<String> nonRetryableCodes) {
        // 优先级：显式传入 > Nacos 配置
        Collection<String> effectiveCodes = (nonRetryableCodes != null)
                ? nonRetryableCodes
                : param.getNonRetryableCodes();

        return doRetry(callable, param.getMaxRetries(), param.getIntervalMs(),
                param.getBackoffMultiplier(), param.getMaxWaitMs(), effectiveCodes);
    }

    // ==================== 核心实现 ====================

    private static <T> T doRetry(Callable<T> callable, int maxRetries, long intervalMs,
                                  double multiplier, long maxWaitMs,
                                  Collection<String> nonRetryableCodes) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                T result = callable.call();
                if (result != null) {
                    if (attempt > 0) {
                        log.info("[AppRetryUtil] 重试成功，第 {} 次重试", attempt);
                    }
                    return result;
                }
                log.warn("[AppRetryUtil] 执行返回为空，第 {}/{} 次", attempt + 1, maxRetries + 1);
            } catch (BizException e) {
                // 命中不可重试错误码 → 直接抛出
                if (nonRetryableCodes != null
                        && !nonRetryableCodes.isEmpty()
                        && nonRetryableCodes.contains(e.getErrorCode().getCode())) {
                    log.warn("[AppRetryUtil] 命中不可重试错误码 [{}]，直接抛出: {}",
                            e.getErrorCode().getCode(), e.getMessage());
                    throw e;
                }
                // 未命中 → 继续重试
                lastException = e;
                log.warn("[AppRetryUtil] 可重试业务异常，第 {}/{} 次，code={}，异常：{}",
                        attempt + 1, maxRetries + 1, e.getErrorCode().getCode(), e.getMessage());
            } catch (Exception e) {
                // 其他异常（IO、网络抖动等）→ 继续重试
                lastException = e;
                log.warn("[AppRetryUtil] 执行异常，第 {}/{} 次，异常：{}",
                        attempt + 1, maxRetries + 1, e.getMessage());
            }

            if (attempt < maxRetries) {
                long wait = computeWait(attempt, intervalMs, multiplier, maxWaitMs);
                log.info("[AppRetryUtil] 等待 {}ms 后发起第 {} 次重试", wait, attempt + 1);
                sleep(wait);
            }
        }

        log.error("[AppRetryUtil] 全部重试失败，共 {} 次，基础间隔 {}ms，乘数 {}",
                maxRetries + 1, intervalMs, multiplier, lastException);
        return null;
    }

    // ==================== 工具方法 ====================

    private static long computeWait(int attempt, long intervalMs, double multiplier, long maxWaitMs) {
        double base = intervalMs * Math.pow(multiplier, attempt);
        long wait = Math.min((long) base, maxWaitMs);
        long jitter = (long) (wait * Math.random() * 0.1);
        return wait + jitter;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[AppRetryUtil] 重试等待被中断，终止后续重试");
        }
    }
}

