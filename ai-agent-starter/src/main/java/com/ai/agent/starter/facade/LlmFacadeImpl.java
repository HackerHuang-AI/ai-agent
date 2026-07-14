package com.ai.agent.starter.facade;

import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.client.dto.LlmFacadeContent;
import com.ai.agent.client.dto.LlmFacadeMessage;
import com.ai.agent.client.dto.LlmFacadeRequest;
import com.ai.agent.client.dto.LlmFacadeResponse;
import com.ai.agent.client.facade.LlmFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * LlmFacade Dubbo Provider 实现（Triple 协议）。
 *
 * <p>ai-agent 对外暴露的 LLM 调用能力入口，
 * 供 ai-knowledge、ai-analysis 等 Consumer 通过 {@code @DubboReference} 调用。</p>
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
        validate(request);
        log.info("[LlmFacade] chat, platform={}, model={}", request.getPlatform(), request.getModelCode());

        LlmRequest llmRequest = buildLlmRequest(request, false);
        LlmResponse resp = llmRouter.chat(request.getPlatform(), llmRequest);

        LlmChoice firstChoice = (resp.getChoices() != null && !resp.getChoices().isEmpty())
                ? resp.getChoices().get(0) : null;
        LlmUsage usage = resp.getUsage();

        LlmFacadeResponse facadeResponse = new LlmFacadeResponse();
        facadeResponse.setContent(firstChoice != null ? firstChoice.getContent() : null);
        facadeResponse.setModelCode(resp.getModelCode());
        facadeResponse.setInputTokens(usage != null && usage.getInputTokens() != null ? usage.getInputTokens() : 0);
        facadeResponse.setOutputTokens(usage != null && usage.getOutputTokens() != null ? usage.getOutputTokens() : 0);
        facadeResponse.setFinishReason(firstChoice != null ? firstChoice.getFinishReason() : null);
        facadeResponse.setExtraData(resp.getExtraData());
        return facadeResponse;
    }

    @Override
    public void chatStream(LlmFacadeRequest request, StreamObserver<String> responseObserver) {
        validate(request);
        log.info("[LlmFacade] chatStream, platform={}, model={}", request.getPlatform(), request.getModelCode());

        LlmRequest llmRequest = buildLlmRequest(request, true);

        try {
            llmRouter.chatStream(request.getPlatform(), llmRequest, chunk -> {
                if (chunk == null) {
                    // null 是流结束信号
                    responseObserver.onCompleted();
                } else if ("[ERROR]".equals(chunk)) {
                    responseObserver.onError(new RuntimeException("LLM platform stream error"));
                } else {
                    responseObserver.onNext(chunk);
                }
            });
        } catch (Exception e) {
            log.error("[LlmFacade] chatStream 异常, platform={}", request.getPlatform(), e);
            responseObserver.onError(e);
        }
    }

    // ==================== 私有方法 ====================

    private void validate(LlmFacadeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }
        if (request.getPlatform() == null || request.getPlatform().isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空");
        }
    }

    /** 将 Facade DTO 组装为领域 LlmRequest */
    private LlmRequest buildLlmRequest(LlmFacadeRequest request, boolean stream) {
        return LlmRequest.builder()
                .apiKey(request.getApiKey())
                .endpoint(request.getEndpoint())
                .modelCode(request.getModelCode())
                .messages(toMessages(request.getMessages()))
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .topK(request.getTopK())
                .frequencyPenalty(request.getFrequencyPenalty())
                .maxTokens(request.getMaxTokens())
                .extraParams(request.getExtraParams())
                .stream(stream)
                .build();
    }

    /**
     * 将 Facade 消息列表转换为领域 LlmMessage 列表，支持纯文本和多模态。
     *
     * <p>转换规则：
     * <ul>
     *   <li>{@code contents} 非空 → 多模态，逐块映射为 {@link MessageContent}</li>
     *   <li>{@code contents} 为空 → 回退到 {@code content} 纯文本</li>
     * </ul>
     */
    private static List<LlmMessage> toMessages(List<LlmFacadeMessage> facadeMessages) {
        if (facadeMessages == null) return List.of();
        return facadeMessages.stream()
                .map(m -> LlmMessage.builder()
                        .role(m.getRole())
                        .contents(toContents(m))
                        .build())
                .toList();
    }

    /**
     * 将单条 Facade 消息的内容转换为 {@link MessageContent} 列表。
     * contents 非空时多模态优先，否则回退纯文本。
     */
    private static List<MessageContent> toContents(LlmFacadeMessage m) {
        if (m.getContents() != null && !m.getContents().isEmpty()) {
            // 多模态：逐块转换
            return m.getContents().stream()
                    .map(LlmFacadeImpl::toMessageContent)
                    .toList();
        }
        // 纯文本回退
        return List.of(new MessageContent(ContentTypeEnum.TEXT, m.getContent(), null));
    }

    /** 将 {@link LlmFacadeContent} 转换为领域 {@link MessageContent} */
    private static MessageContent toMessageContent(LlmFacadeContent c) {
        ContentTypeEnum type = switch (c.getType() == null ? "" : c.getType().toLowerCase()) {
            case "image" -> ContentTypeEnum.IMAGE;
            case "file"  -> ContentTypeEnum.FILE;
            case "video" -> ContentTypeEnum.VIDEO;
            default      -> ContentTypeEnum.TEXT;
        };
        return new MessageContent(type, c.getValue(), c.getDetail());
    }
}

