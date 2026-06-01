package com.ai.agent.infrastructure.llm;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.infrastructure.llm.adapter.LlmAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @Description: LLM 适配器工厂，根据平台编码路由到对应的 Adapter
 *               新增平台只需实现 LlmAdapter 接口并注册为 Spring Bean，工厂自动感知，无需修改此类
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.llm
 * @ClassName: LlmAdapterFactory
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Slf4j
@Component
public class LlmAdapterFactory {

    /** key = platformCode，value = 对应 Adapter */
    private final Map<String, LlmAdapter> adapterMap;

    /**
     * Spring 启动时自动注入所有 LlmAdapter 实现，构建路由 Map
     */
    public LlmAdapterFactory(List<LlmAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(LlmAdapter::platformCode, Function.identity()));
        log.info("LlmAdapterFactory 初始化完成，已注册平台: {}", adapterMap.keySet());
    }

    /**
     * 根据平台编码获取对应 Adapter
     *
     * @param platformCode 平台编码，对应 llm_platform.code
     * @return 对应平台的 LlmAdapter
     * @throws BizException 平台未注册时抛出
     */
    public LlmAdapter getAdapter(String platformCode) {
        LlmAdapter adapter = adapterMap.get(platformCode);
        if (adapter == null) {
            log.error("未找到平台适配器, platformCode={}", platformCode);
            throw new BizException(ErrorCodeEnum.LLM_PLATFORM_NOT_SUPPORTED);
        }
        return adapter;
    }
}

