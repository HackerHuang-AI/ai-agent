package com.ai.agent.starter.facade;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.utils.ResponsesStreamUtil;
import com.ai.agent.client.dto.*;
import com.ai.agent.client.facade.LlmFacade;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.config.annotation.DubboService;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LlmFacade Dubbo Provider 实现（Triple 协议）。
 *
 * <p>ai-agent 对外暴露的 LLM 调用能力入口，
 * 供 ai-knowledge、ai-analysis 等 Consumer 通过 {@code @DubboReference} 调用。</p>
 *
 * <p>职责：DTO ↔ 领域模型转换 + 委托 {@link LlmRouter} 路由到对应平台 Service 执行。</p>
 *
 * @author HUANGcong
 * @since 2026-05-29
 */
@Slf4j
@DubboService(group = "ai-agent", version = "1.0.0")
@RequiredArgsConstructor
public class LlmFacadeImpl implements LlmFacade {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmRouter llmRouter;

    @Override
    public LlmFacadeResponse chat(LlmFacadeRequest request) {
        validate(request);
        log.info("[LlmFacade] chat, platform={}, model={}", request.getPlatform(), request.getModelCode());

        LlmRequest llmRequest = buildLlmRequest(request, false);
        return toFacadeResponse(llmRouter.chat(request.getPlatform(), llmRequest));
    }

    @Override
    public LlmFacadeResponsesResponse responses(LlmFacadeResponsesRequest request) {
        validateResponses(request);
        log.info("[LlmFacade] responses, platform={}, model={}", request.getPlatform(), request.getModel());
        LlmResponse response = llmRouter.responses(request.getPlatform(), buildResponsesRequest(request));
        return toFacadeResponsesResponse(response);
    }

    @Override
    public void responsesStream(LlmFacadeResponsesRequest request, StreamObserver<String> responseObserver) {
        AtomicBoolean terminated = new AtomicBoolean();
        try {
            validateResponses(request);
            var consumer = ResponsesStreamUtil.cancellable(chunk -> {
                if (terminated.get()) return;
                if (chunk == null) {
                    if (terminated.compareAndSet(false, true)) responseObserver.onCompleted();
                    return;
                }
                try {
                    if ("[ERROR]".equals(chunk)) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                    String type = MAPPER.readTree(chunk).path("type").asText();
                    responseObserver.onNext(chunk);
                    if ("error".equals(type) || "response.failed".equals(type)) {
                        if (terminated.compareAndSet(false, true)) {
                            responseObserver.onError(new RuntimeException("LLM Responses stream error: " + type));
                        }
                    }
                } catch (Exception e) {
                    if (terminated.compareAndSet(false, true)) responseObserver.onError(e);
                    throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
                }
            });
            var cancellation = RpcContext.getCancellationContext();
            if (cancellation != null) cancellation.addListener(context -> {
                terminated.set(true);
                consumer.cancel();
            });
            if (!consumer.isCancelled()) {
                llmRouter.responsesStream(request.getPlatform(), buildResponsesRequest(request), consumer);
            }
        } catch (Exception e) {
            if (terminated.compareAndSet(false, true)) responseObserver.onError(e);
        }
    }

    @Override
    public void chatStream(LlmFacadeRequest request, StreamObserver<String> responseObserver) {
        try {
            validate(request);
            log.info("[LlmFacade] chatStream, platform={}, model={}", request.getPlatform(), request.getModelCode());
            LlmRequest llmRequest = buildLlmRequest(request, true);
            llmRouter.chatStream(request.getPlatform(), llmRequest, chunk -> {
                if (chunk == null) {
                    // null 是流结束信号
                    responseObserver.onCompleted();
                } else if (chunk.startsWith("[ERROR]")) {
                    responseObserver.onError(new RuntimeException("LLM platform stream error"));
                } else {
                    // [TOOL_CALLS]{...} 是服务端聚合后的工具调用结果，直接透传给 Consumer
                    // Consumer 侧通过 chunk.startsWith("[TOOL_CALLS]") 识别，截取后解析 JSON
                    responseObserver.onNext(chunk);
                }
            });
        } catch (Exception e) {
            log.error("[LlmFacade] chatStream 异常, platform={}", request == null ? null : request.getPlatform(), e);
            responseObserver.onError(e);
        }
    }

    @Override
    public LlmFacadeInteractionsResponse interactions(LlmFacadeRequest request) {
        validateInteractions(request);
        log.info("[LlmFacade] interactions, platform={}, model={}", request.getPlatform(), request.getModel());
        return toFacadeInteractionsResponse(llmRouter.interactions(request.getPlatform(), buildInteractionsRequest(request)));
    }

    @Override
    public void interactionsStream(LlmFacadeRequest request, StreamObserver<String> responseObserver) {
        AtomicBoolean terminated = new AtomicBoolean();
        try {
            validateInteractions(request);
            log.info("[LlmFacade] interactionsStream, platform={}, model={}", request.getPlatform(), request.getModel());
            var consumer = ResponsesStreamUtil.cancellable(chunk -> {
                if (terminated.get()) return;
                if (chunk == null) {
                    if (terminated.compareAndSet(false, true)) responseObserver.onCompleted();
                    return;
                }
                try {
                    if ("[ERROR]".equals(chunk)) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                    var event = MAPPER.readTree(chunk);
                    String type = event.path("event_type").asText();
                    String status = event.path("interaction").path("status").asText(event.path("status").asText());
                    if (terminated.get()) return;
                    responseObserver.onNext(chunk);
                    if ("error".equals(type) || "interaction.failed".equals(type) || "interaction.cancelled".equals(type)
                            || "failed".equals(status) || "cancelled".equals(status)) {
                        if (terminated.compareAndSet(false, true)) {
                            responseObserver.onError(new BizException(ErrorCodeEnum.LLM_CALL_FAILED));
                        }
                    }
                } catch (Exception e) {
                    if (terminated.compareAndSet(false, true)) responseObserver.onError(e);
                    throw e instanceof RuntimeException ? (RuntimeException) e : new IllegalStateException(e);
                }
            });
            var cancellation = RpcContext.getCancellationContext();
            if (cancellation != null) cancellation.addListener(context -> {
                terminated.set(true);
                consumer.cancel();
            });
            if (!consumer.isCancelled()) {
                llmRouter.interactionsStream(request.getPlatform(), buildInteractionsRequest(request), consumer);
            }
        } catch (Exception e) {
            if (terminated.compareAndSet(false, true)) responseObserver.onError(e);
        }
    }

    private void checkPlatform(String platform) {
        if (!StringUtils.hasText(platform)) throw new IllegalArgumentException("platform 不能为空");
    }

    private void validateInteractions(LlmFacadeRequest request) {
        if (request == null) throw new IllegalArgumentException("request 不能为 null");
        checkPlatform(request.getPlatform());
    }

    private LlmRequest buildInteractionsRequest(LlmFacadeRequest request) {
        return buildLlmRequest(request, false);
    }

    private static LlmFacadeInteractionsResponse toFacadeInteractionsResponse(LlmInteractionsResponse response) {
        LlmFacadeInteractionsResponse result = new LlmFacadeInteractionsResponse();
        result.setId(response.getId());
        result.setModel(response.getModel());
        result.setStatus(response.getStatus());
        result.setCreated(response.getCreated());
        result.setUpdated(response.getUpdated());
        result.setSteps(response.getSteps());
        result.setOutputText(response.getOutputText());
        result.setContinuationToken(response.getContinuationToken());
        LlmUsage usage = response.getUsage();
        if (usage != null) {
            result.setInputTokens(usage.getInputTokens());
            result.setOutputTokens(usage.getOutputTokens());
            result.setTotalTokens(usage.getTotalTokens());
            result.setUsage(MAPPER.convertValue(usage, new TypeReference<Map<String, Object>>() {}));
        }
        return result;
    }

    // ==================== 私有方法 ====================

    private static LlmFacadeResponse toFacadeResponse(LlmResponse resp) {
        LlmChoice firstChoice = (resp.getChoices() != null && !resp.getChoices().isEmpty())
                ? resp.getChoices().get(0) : null;
        LlmUsage usage = resp.getUsage();

        LlmFacadeResponse facadeResponse = new LlmFacadeResponse();
        facadeResponse.setContent(firstChoice != null ? firstChoice.getContent() : null);
        facadeResponse.setReasoningContent(firstChoice != null ? firstChoice.getReasoningContent() : null);
        facadeResponse.setThinkingBlocks(firstChoice != null ? firstChoice.getThinkingBlocks() : null);
        facadeResponse.setModelCode(resp.getModelCode());
        facadeResponse.setInputTokens(usage != null && usage.getInputTokens() != null ? usage.getInputTokens() : 0);
        facadeResponse.setOutputTokens(usage != null && usage.getOutputTokens() != null ? usage.getOutputTokens() : 0);
        facadeResponse.setFinishReason(firstChoice != null ? firstChoice.getFinishReason() : null);
        facadeResponse.setExtraData(resp.getExtraData());
        facadeResponse.setToolCalls(firstChoice != null ? toToolCallDtos(firstChoice.getToolCalls()) : null);
        return facadeResponse;
    }

    private static List<LlmToolCallDto> toToolCallDtos(List<LlmToolCall> toolCalls) {
        if (toolCalls == null) return null;
        return toolCalls.stream()
                .map(tc -> {
                    LlmToolCallDto dto = new LlmToolCallDto(tc.getId(), tc.getName(), tc.getArguments());
                    dto.setExtraContent(tc.getExtraContent());
                    return dto;
                })
                .toList();
    }

    private static LlmFacadeResponsesResponse toFacadeResponsesResponse(LlmResponse response) {
        LlmUsage usage = response.getUsage();
        LlmFacadeResponsesResponse result = new LlmFacadeResponsesResponse();
        result.setRequestId(response.getRequestId());
        result.setModelCode(response.getModelCode());
        result.setStatus(response.getStatus());
        result.setMaxOutputTokens(response.getMaxOutputTokens());
        result.setInputTokens(usage != null && usage.getInputTokens() != null ? usage.getInputTokens() : 0);
        result.setOutputTokens(usage != null && usage.getOutputTokens() != null ? usage.getOutputTokens() : 0);
        List<Map<String, Object>> output = new ArrayList<>();
        if (response.getOutput() != null) {
            for (LlmOutputItem item : response.getOutput()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", item.getId());
                value.put("type", item.getType());
                value.put("status", item.getStatus());
                value.put("role", item.getRole());
                value.put("call_id", item.getCallId());
                value.put("name", item.getName());
                value.put("arguments", item.getArguments());
                value.put("summary", MAPPER.convertValue(item.getSummary(), new TypeReference<List<Map<String, Object>>>() {}));
                value.put("content", MAPPER.convertValue(item.getContent(), new TypeReference<List<Map<String, Object>>>() {}));
                value.put("encrypted_content", item.getEncryptedContent());
                value.values().removeIf(Objects::isNull);
                output.add(value);
            }
        }
        result.setOutput(output);
        return result;
    }

    private static LlmResponsesRequest buildResponsesRequest(LlmFacadeResponsesRequest request) {
        return LlmResponsesRequest.builder()
                .apiKey(request.getApiKey()).endpoint(request.getEndpoint()).model(request.getModel())
                .input(toResponsesInput(request.getInput())).instructions(request.getInstructions())
                .temperature(request.getTemperature()).topP(request.getTopP()).maxOutputTokens(request.getMaxOutputTokens())
                .tools(request.getTools()).toolChoice(request.getToolChoice()).extraParams(request.getExtraParams()).build();
    }

    private static List<LlmResponsesInput> toResponsesInput(List<LlmFacadeResponsesInput> input) {
        if (input == null) return null;
        return input.stream().map(item -> {
            LlmResponsesInput target = MAPPER.convertValue(item, LlmResponsesInput.class);
            // outputContents 被 JsonIgnore 排除，须单独保留工具结果的多模态内容。
            target.setOutputContents(item.getOutputContents() == null ? null : item.getOutputContents().stream()
                    .map(content -> MAPPER.convertValue(content, LlmResponsesContent.class)).toList());
            return target;
        }).toList();
    }

    private void validateResponses(LlmFacadeResponsesRequest request) {
        if (request == null || request.getPlatform() == null || request.getPlatform().isBlank()) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        if (request.getInput() == null || request.getInput().isEmpty()) {
            throw new IllegalArgumentException("input 不能为空");
        }
    }

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
        if (request.getMessages() != null && request.getMessages().stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("messages 不能包含 null");
        }
    }

    /** 将 Facade DTO 组装为领域 LlmRequest */
    private LlmRequest buildLlmRequest(LlmFacadeRequest request, boolean stream) {
        return LlmRequest.builder()
                .apiKey(request.getApiKey())
                .endpoint(request.getEndpoint())
                .modelCode(request.getModelCode())
                .messages(toMessages(request.getMessages()))
                .model(request.getModel())
                .maxOutputTokens(request.getMaxOutputTokens())
                .instructions(request.getInstructions())
                .generationConfig(request.getGenerationConfig())
                .previousInteractionId(request.getPreviousInteractionId())
                .continuationToken(request.getContinuationToken())
                .store(request.getStore())
                .background(request.getBackground())
                // Client 步骤及内容节点为独立镜像，按相同字段树转换，不归一化内容类型。
                .interactionSteps(request.getInteractionSteps() == null ? null : request.getInteractionSteps().stream()
                        .map(step -> MAPPER.convertValue(step, LlmInteractionStep.class)).toList())
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .topK(request.getTopK())
                .frequencyPenalty(request.getFrequencyPenalty())
                .maxTokens(request.getMaxTokens())
                .extraParams(request.getExtraParams())
                .tools(request.getTools())
                .toolChoice(request.getToolChoice())
                .stream(stream)
                .build();
    }

    /**
     * 将 Facade 消息列表转换为领域 LlmMessage 列表，支持纯文本和多模态。
     *
     * <p>转换规则：
     * <ul>
     *   <li>{@code contents} 非空 → 多模态，逐块映射为 {@link MessageContent}</li>
     *   <li>{@code contents} 为空、{@code content} 非空 → 回退到 {@code content} 纯文本</li>
     *   <li>{@code toolCalls} 非空且 {@code contents}/{@code content} 均为空 → 无正文，仅工具调用</li>
     * </ul>
     * 协议上 assistant 消息的 content 与 tool_calls 可同时存在，故不会互相覆盖。
     */
    private static List<LlmMessage> toMessages(List<LlmFacadeMessage> facadeMessages) {
        if (facadeMessages == null) return null;
        return facadeMessages.stream()
                .map(m -> {
                    boolean hasText = (m.getContents() != null && !m.getContents().isEmpty())
                            || m.getContent() != null;
                    return LlmMessage.builder()
                            .role(m.getRole())
                            .name(m.getName())
                            .audio(MAPPER.convertValue(m.getAudio(), LlmMessage.AudioReference.class))
                            .reasoningContent(m.getReasoningContent())
                            .thinkingBlocks(m.getThinkingBlocks())
                            .contents(hasText ? toContents(m) : null)
                            .toolCalls(toDomainToolCalls(m.getToolCalls()))
                            .toolCallId(m.getToolCallId())
                            .build();
                })
                .toList();
    }

    private static List<LlmToolCall> toDomainToolCalls(List<LlmToolCallDto> toolCallDtos) {
        if (toolCallDtos == null) return null;
        return toolCallDtos.stream()
                .map(dto -> LlmToolCall.builder().id(dto.getId()).name(dto.getName()).arguments(dto.getArguments())
                        .extraContent(dto.getExtraContent()).build())
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
        return MAPPER.convertValue(c, MessageContent.class);
    }
}

