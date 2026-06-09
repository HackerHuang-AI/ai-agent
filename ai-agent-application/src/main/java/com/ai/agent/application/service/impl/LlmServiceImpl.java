package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.LlmModelBO;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.service.LlmModelQueryService;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.service.LlmPlatformService;
import com.ai.agent.application.service.LlmPlatformServiceFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.function.Consumer;

/**
 * @Description: LLM 统一调用服务实现
 *               流程：校验入参 → 查模型配置 → 查 API Key → 路由平台 Service → 调用 → 持久化记录
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: LlmServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmServiceImpl implements LlmService {

    private final LlmPlatformServiceFactory platformServiceFactory;
    private final LlmModelQueryService llmModelQueryService;

    @Override
    public LlmResponse chat(LlmRequest request) {
        validate(request);

        LlmModelBO model = llmModelQueryService.getEnabledModel(request.getModelCode());
        String apiKey = llmModelQueryService.getApiKey(model.getPlatformCode());

        LlmPlatformService platformService = platformServiceFactory.getService(model.getPlatformCode());
        LlmResponse response = platformService.chat(request, apiKey, model.getApiEndpoint());

        log.info("LLM 调用成功, modelCode={}, inputTokens={}, outputTokens={}",
                request.getModelCode(), response.getInputTokens(), response.getOutputTokens());
        return response;
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        validate(request);

        LlmModelBO model = llmModelQueryService.getEnabledModel(request.getModelCode());
        String apiKey = llmModelQueryService.getApiKey(model.getPlatformCode());

        LlmPlatformService platformService = platformServiceFactory.getService(model.getPlatformCode());
        platformService.chatStream(request, apiKey, model.getApiEndpoint(), chunkConsumer);
    }

    // ==================== 私有方法 ====================

    private void validate(LlmRequest request) {
        if (request == null || !StringUtils.hasText(request.getModelCode())) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }
}

