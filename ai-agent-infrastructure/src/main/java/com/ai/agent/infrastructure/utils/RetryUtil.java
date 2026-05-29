package com.ai.agent.infrastructure.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;

/**
 * @Description: 重试工具类，采用递增等待时间策略（实际等待 = interval * 当前重试次数）
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: RetryUtil
 * @Author: HUANGcong
 * @Date: Created in 2026/5/13
 * @Version: 1.0
 */
@Slf4j
public class RetryUtil {

    private RetryUtil() {}

    /**
     * 重试执行方法。
     * 执行策略：首次执行 + 最多 maxRetries 次重试，共最多 maxRetries + 1 次执行。
     * 每次重试前递增等待：第 n 次重试等待 interval * n 毫秒。
     */
    public static <T> T retry(Callable<T> callable, int maxRetries, long interval) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                T result = callable.call();
                if (result != null) {
                    if (attempt > 0) {
                        log.info("重试成功，第 {} 次重试", attempt);
                    }
                    return result;
                }
                log.warn("执行返回为空，第 {}/{} 次", attempt + 1, maxRetries + 1);
            } catch (Exception e) {
                lastException = e;
                log.warn("执行异常，第 {}/{} 次，异常：{}", attempt + 1, maxRetries + 1, e.getMessage());
            }

            if (attempt < maxRetries) {
                long sleepMs = interval * (attempt + 1);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("重试被中断，第 {} 次等待中断，终止后续重试", attempt + 1);
                    return null;
                }
            }
        }

        log.error("全部重试失败，共 {} 次，基础间隔 {}ms", maxRetries + 1, interval, lastException);
        return null;
    }
}

