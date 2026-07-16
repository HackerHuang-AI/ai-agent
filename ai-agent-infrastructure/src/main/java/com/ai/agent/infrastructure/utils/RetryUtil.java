package com.ai.agent.infrastructure.utils;

import com.ai.agent.infrastructure.config.param.RetryParam;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
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
 *   <li>callable 抛出 checked 异常（IOException 等，非 RuntimeException）</li>
 *   <li>callable 返回 null</li>
 *   <li>callable 抛出 BizException，且其 errorCode.code 不在 nonRetryableCodes 中</li>
 * </ul>
 *
 * <p>不触发重试的条件：
 * <ul>
 *   <li>callable 抛出 BizException，且其 errorCode.code 在 nonRetryableCodes 中
 *       （如认证失败 2002009、参数错误 2001001）—— 直接向上抛出</li>
 *   <li>callable 抛出其他 RuntimeException —— 直接向上抛出</li>
 * </ul>
 *
 * <p>nonRetryableCodes 由 {@link RetryParam#getNonRetryableCodes()} 提供，
 * 存储 ErrorCodeEnum.code 字段值（如 "2002009"），通过 Nacos 配置，为空时不限制。
 * RetryUtil 通过反射读取 BizException.getErrorCode().getCode()，
 * 避免 infrastructure → application 的循环依赖。
 *
 * <p>重试参数通过 {@link RetryParam} 传入，调用方从 RetryConfig 实时读取 Nacos 值，保证热更新生效。
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
     * 参数从 RetryConfig 实时读取，热更新立即生效。
     * nonRetryableCodes 从 param 中读取，为空时不限制（所有 BizException 均可重试）。
     */
    public static <T> T retry(Callable<T> callable, RetryParam param) {
        return retry(callable, param.getMaxRetries(), param.getIntervalMs(),
                param.getBackoffMultiplier(), param.getMaxWaitMs(),
                param.getNonRetryableCodes());
    }

    /**
     * 指数退避重试，兼容旧调用方式（不限制 BizException 重试）。
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
        return retry(callable, maxRetries, intervalMs, multiplier, maxWaitMs, null);
    }

    /**
     * 核心重试实现。
     *
     * @param nonRetryableCodes BizException 中不触发重试的错误码集合（ErrorCodeEnum.code 值）；
     *                          null 或空时对 BizException 不做限制，均触发重试
     */
    private static <T> T retry(Callable<T> callable, int maxRetries, long intervalMs,
                                double multiplier, long maxWaitMs,
                                Collection<String> nonRetryableCodes) {
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
            } catch (RuntimeException e) {
                // 只有"可重试的 BizException"才继续重试，其余全部直接向上抛出：
                // 1. 非 BizException（其他 RuntimeException）—— 直接抛
                // 2. BizException 且 errorCode 在 nonRetryableCodes 中 —— 直接抛
                if (isBizException(e) && !isNonRetryableBizException(e, nonRetryableCodes)) {
                    // 可重试的 BizException，继续重试
                    lastException = e;
                    log.warn("[RetryUtil] 可重试业务异常，第 {}/{} 次，异常：{}", attempt + 1, maxRetries + 1, e.getMessage());
                } else {
                    log.warn("[RetryUtil] 不可重试异常，直接抛出: {}", e.getMessage());
                    throw e;
                }
            } catch (Exception e) {
                // checked 异常（IOException 等）—— 继续重试
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
     * 等待指定时间。
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[RetryUtil] 重试等待被中断，终止后续重试");
        }
    }

    /**
     * 判断异常是否为 BizException（通过类名判断，避免循环依赖）。
     */
    private static boolean isBizException(RuntimeException e) {
        return "BizException".equals(e.getClass().getSimpleName());
    }

    /**
     * 判断异常是否为"命中不可重试码的 BizException"。
     *
     * <p>通过反射读取 BizException.getErrorCode().getCode()，与 nonRetryableCodes 比对，
     * 避免 infrastructure 模块反向依赖 application 模块。
     *
     * <p>nonRetryableCodes 为 null 或空时，直接返回 false（不限制，均可重试）。
     * 若反射失败（类结构不匹配），保守返回 false，继续重试。
     *
     * @param e                 捕获到的 RuntimeException
     * @param nonRetryableCodes 不可重试的错误码集合（ErrorCodeEnum.code 字段值）
     * @return true 表示命中不可重试码，应直接抛出
     */
    private static boolean isNonRetryableBizException(RuntimeException e,
                                                       Collection<String> nonRetryableCodes) {
        if (nonRetryableCodes == null || nonRetryableCodes.isEmpty()) {
            return false;
        }
        if (!isBizException(e)) {
            return false;
        }
        try {
            Object errorCode = e.getClass().getMethod("getErrorCode").invoke(e);
            if (errorCode == null) return false;
            String code = (String) errorCode.getClass().getMethod("getCode").invoke(errorCode);
            return nonRetryableCodes.contains(code);
        } catch (Exception ex) {
            log.warn("[RetryUtil] 反射读取 errorCode 失败，保守继续重试: {}", ex.getMessage());
            return false;
        }
    }
}

