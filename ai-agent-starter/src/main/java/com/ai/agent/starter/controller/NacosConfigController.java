package com.ai.agent.starter.controller;


import com.ai.agent.infrastructure.config.NacosConfig;
import com.ai.agent.starter.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @Description: Nacos 配置查询接口，支持按 dataId 查看当前缓存中的配置内容，用于调试和验证配置是否生效。
 * @ProjectName: ai-analysis
 * @Package: com.ai.analysis.controller
 * @ClassName: NacosConfigController
 * @Author: HUANGcong
 * @Date: Created in 2026/5/17
 * @Version: 1.0
 */
@Slf4j
@RestController
@RequestMapping("/api/nacos")
public class NacosConfigController {

    private final NacosConfig nacosConfig;
    private final ThreadPoolExecutor doubaoStreamExecutor;
    private final ThreadPoolExecutor deepseekStreamExecutor;

    public NacosConfigController(
            NacosConfig nacosConfig,
            @Qualifier("doubaoStreamExecutor") ThreadPoolExecutor doubaoStreamExecutor,
            @Qualifier("deepseekStreamExecutor") ThreadPoolExecutor deepseekStreamExecutor) {
        this.nacosConfig = nacosConfig;
        this.doubaoStreamExecutor = doubaoStreamExecutor;
        this.deepseekStreamExecutor = deepseekStreamExecutor;
    }

    /**
     * 根据 dataId 查询该文件下所有缓存的配置项。
     *
     * <pre>
     * 请求示例：
     *   POST /ai-analysis/api/nacos/config
     *   Body: { "dataId": "ai-analysis-retry.json" }
     *
     * 响应示例：
     *   {
     *     "code": "00",
     *     "message": "success",
     *     "data": {
     *       "maxRetries": "3",
     *       "interval": "1000"
     *     }
     *   }
     * </pre>
     */
    @PostMapping("/config")
    public Result<Map<String, String>> getConfigByDataId(@RequestBody Map<String, String> body) {
        log.info("getConfigByDataId 请求参数：{}", body);
        String dataId = body.get("dataId");
        if (dataId == null || dataId.isBlank()) {
            return Result.error("dataId 不能为空");
        }
        Map<String, String> config = nacosConfig.getCacheByDataId(dataId);
        if (config == null) {
            return Result.error("DataId 不存在或尚未加载：" + dataId);
        }
        return Result.success(config);
    }

    /**
     * 查看 LLM 流式线程池当前运行参数，用于验证 Nacos 热更新是否生效。
     *
     * <pre>
     * 请求示例：
     *   GET /ai-agent/api/nacos/thread-pool
     *
     * 响应示例：
     *   {
     *     "doubao-stream-pool":   { "corePoolSize": 10, "maxPoolSize": 50, "activeCount": 3, "queueSize": 12, "completedTaskCount": 100 },
     *     "deepseek-stream-pool": { "corePoolSize": 5,  "maxPoolSize": 30, "activeCount": 1, "queueSize": 0,  "completedTaskCount": 50  }
     *   }
     * </pre>
     */
    @GetMapping("/thread-pool")
    public Result<Map<String, Map<String, Object>>> getThreadPoolStats() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        result.put("doubao-stream-pool",   buildStats(doubaoStreamExecutor));
        result.put("deepseek-stream-pool", buildStats(deepseekStreamExecutor));
        return Result.success(result);
    }

    private Map<String, Object> buildStats(ThreadPoolExecutor executor) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("corePoolSize",        executor.getCorePoolSize());
        stats.put("maxPoolSize",         executor.getMaximumPoolSize());
        stats.put("activeCount",         executor.getActiveCount());
        stats.put("queueSize",           executor.getQueue().size());
        stats.put("completedTaskCount",  executor.getCompletedTaskCount());
        return stats;
    }
}

