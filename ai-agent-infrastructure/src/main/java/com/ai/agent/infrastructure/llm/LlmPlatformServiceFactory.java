package com.ai.agent.infrastructure.llm;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.infrastructure.llm.service.LlmPlatformService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Description: LLM 平台服务工厂，根据平台编码路由到对应的 LlmPlatformService
 *               新增平台只需实现 LlmPlatformService 接口并注册为 Spring Bean，工厂自动感知，无需修改此类
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.llm
 * @ClassName: LlmPlatformServiceFactory
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Slf4j
@Component
public class LlmPlatformServiceFactory {

    /** key = platformCode，value = 对应 LlmPlatformService */
    private final Map<String, LlmPlatformService> serviceMap;

    /**
     * Spring 启动时自动注入所有 LlmPlatformService 实现，构建路由 Map
     */
    public LlmPlatformServiceFactory(List<LlmPlatformService> services) {
        this.serviceMap = services.stream()
                .collect(Collectors.toMap(LlmPlatformService::platformCode, Function.identity()));
        log.info("LlmPlatformServiceFactory 初始化完成，已注册平台: {}", serviceMap.keySet());
    }

    /**
     * 根据平台编码获取对应 LlmPlatformService
     *
     * @param platformCode 平台编码，对应 llm_platform.code
     * @return 对应平台的 LlmPlatformService
     * @throws BizException 平台未注册时抛出
     */
    public LlmPlatformService getService(String platformCode) {
        LlmPlatformService service = serviceMap.get(platformCode);
        if (service == null) {
            log.error("未找到平台服务, platformCode={}", platformCode);
            throw new BizException(ErrorCodeEnum.LLM_PLATFORM_NOT_SUPPORTED);
        }
        return service;
    }
}

