package com.ai.agent.starter.facade;

import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.client.dto.LlmFacadeMessage;
import com.ai.agent.client.dto.LlmFacadeRequest;
import com.ai.agent.client.dto.LlmFacadeResponse;
import com.ai.agent.client.facade.LlmFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * LlmFacade Dubbo Provider 实现。
 *
 * <p>ai-agent 对外暴露的 LLM 调用能力入口（Dubbo 协议），
 * 供 ai-knowledge、ai-analysis 等 Consumer 通过 @DubboReference 调用。</p>
 *
 * <p>职责：DTO ↔ 领域模型转换 + 委托 {@link LlmRouter} 路由到对应平台 Service 执行。</p>
 */
@Slf4j
@DubboService
@RequiredArgsConstructor
public class LlmFacadeImpl implements LlmFacade {

    private final LlmRouter llmRouter;

    @Override
    public LlmFacadeResponse chat(LlmFacadeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }
        if (request.getPlatform() == null || request.getPlatform().isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
        log.info("[LlmFacade] Dubbo 调用, platform={}, model={}", request.getPlatform(), request.getModelCode());

        // 1. Facade DTO → 领域 LlmRequest
        LlmRequest llmRequest = LlmRequest.builder()
                .apiKey(request.getApiKey())
                .endpoint(request.getEndpoint())
                .modelCode(request.getModelCode())
                .messages(toMessages(request.getMessages()))
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .maxTokens(request.getMaxTokens())
                .extraParams(request.getExtraParams())
                .stream(false)
                .build();

        // 2. 路由到对应平台执行
        LlmResponse resp = llmRouter.chat(request.getPlatform(), llmRequest);

        // 3. 领域响应 → Facade DTO
        // LlmFacadeResponse 是 Dubbo 对外接口契约，保持字段不变，从新结构中适配取值
        LlmChoice firstChoice = (resp.getChoices() != null && !resp.getChoices().isEmpty())
                ? resp.getChoices().get(0) : null;
        LlmFacadeResponse facadeResponse = new LlmFacadeResponse();
        facadeResponse.setContent(firstChoice != null ? firstChoice.getContent() : null);
        facadeResponse.setModelCode(resp.getModelCode());
        facadeResponse.setInputTokens(resp.getUsage() != null ? resp.getUsage().getInputTokens() : 0);
        facadeResponse.setOutputTokens(resp.getUsage() != null ? resp.getUsage().getOutputTokens() : 0);
        facadeResponse.setFinishReason(firstChoice != null ? firstChoice.getFinishReason() : null);
        facadeResponse.setExtraData(resp.getExtraData());
        return facadeResponse;
    }

    /**
     * 将 Facade 轻量消息 DTO 转换为领域 LlmMessage（纯文本）
     */
    private static List<LlmMessage> toMessages(List<LlmFacadeMessage> facadeMessages) {
        if (facadeMessages == null) return List.of();
        return facadeMessages.stream()
                .map(m -> LlmMessage.builder()
                        .role(m.getRole())
                        .contents(List.of(new MessageContent(ContentTypeEnum.TEXT, m.getContent(), null)))
                        .build())
                .toList();
    }
}

