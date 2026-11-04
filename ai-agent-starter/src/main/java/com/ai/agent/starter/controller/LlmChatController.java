package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.utils.ResponsesStreamUtil;
import com.ai.agent.infrastructure.utils.JsonUtil;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.vo.LlmCredentialVO;
import com.ai.agent.starter.vo.LlmRequestVO;
import com.ai.agent.starter.vo.LlmResponseVO;
import com.ai.agent.starter.vo.LlmResponsesRequestVO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * LLM 统一对话接口，通过 platform 参数路由到对应平台实现。
 *
 *               POST /api/llm/chat              同步对话（文本和图文输入）
 *               POST /api/llm/chat/stream        流式对话（文本和图文输入）
 *               POST /api/llm/responses          Responses API 调用
 *               POST /api/llm/models             模型列表
 *
 * @author HUANGcong
 * @since 2026-07-16
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/llm")
public class LlmChatController {

    private final LlmRouter llmRouter;

    public LlmChatController(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    @PostMapping("/models")
    public Result<LlmModelPage> listModels(@Valid @RequestBody LlmCredentialVO req) {
        checkPlatform(req.getPlatform());
        return Result.success(llmRouter.listModels(req.getPlatform(), req.getApiKey(),
                req.getPageNo(), req.getPageSize()));
    }

    @PostMapping("/chat")
    public Result<LlmResponseVO> chat(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmChat] 开始处理, platform={}, req={}", req.getPlatform(), req);
        LlmResponse response = llmRouter.chat(req.getPlatform(), toServiceRequest(req));
        log.info("[LlmChat] 处理完成, platform={}", req.getPlatform());
        return Result.success(toVO(response));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmChat-stream] 开始处理, platform={}, req={}", req.getPlatform(), req);
        SseEmitter emitter = new SseEmitter(0L);
        llmRouter.chatStream(req.getPlatform(), toServiceRequest(req), buildSseConsumer(emitter, req.getPlatform()));
        return emitter;
    }

    @PostMapping("/responses")
    public Result<LlmResponseVO> responses(@Valid @RequestBody LlmResponsesRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmResponses] 开始处理, platform={}, model={}", req.getPlatform(), req.getModel());
        LlmResponse response = llmRouter.responses(req.getPlatform(), toResponsesRequest(req));
        log.info("[LlmResponses] 处理完成, platform={}", req.getPlatform());
        return Result.success(toVO(response));
    }

    @PostMapping(value = "/responses/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter responsesStream(@Valid @RequestBody LlmResponsesRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmResponses-stream] 开始处理, platform={}, model={}", req.getPlatform(), req.getModel());
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean terminated = new AtomicBoolean();
        var consumer = ResponsesStreamUtil.cancellable(buildResponsesSseConsumer(emitter, req.getPlatform(), terminated));
        Runnable cancel = () -> {
            terminated.set(true);
            consumer.cancel();
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        llmRouter.responsesStream(req.getPlatform(), toResponsesRequest(req), consumer);
        return emitter;
    }

    @PostMapping("/interactions")
    public Result<LlmInteractionsResponse> interactions(@Valid @RequestBody LlmRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmInteractions] platform={}, model={}", req.getPlatform(), req.getModel());
        return Result.success(llmRouter.interactions(req.getPlatform(), toServiceRequest(req)));
    }

    @PostMapping(value = "/interactions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter interactionsStream(@Valid @RequestBody LlmRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmInteractions-stream] platform={}, model={}", req.getPlatform(), req.getModel());
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean terminated = new AtomicBoolean();
        var consumer = ResponsesStreamUtil.cancellable(buildInteractionsSseConsumer(emitter, req.getPlatform(), terminated));
        Runnable cancel = () -> {
            terminated.set(true);
            consumer.cancel();
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(error -> cancel.run());
        llmRouter.interactionsStream(req.getPlatform(), toServiceRequest(req), consumer);
        return emitter;
    }

    private Consumer<String> buildInteractionsSseConsumer(SseEmitter emitter, String platform, AtomicBoolean terminated) {
        return data -> {
            if (terminated.get()) return;
            if (data == null) {
                if (terminated.compareAndSet(false, true)) emitter.complete();
                return;
            }
            try {
                String payload = "[ERROR]".equals(data)
                        ? "{\"event_type\":\"error\",\"code\":\"" + ErrorCodeEnum.LLM_CALL_FAILED.getCode()
                                + "\",\"message\":\"LLM call failed\"}" : data;
                var event = JsonUtil.readTree(payload);
                String type = event.path("event_type").asText();
                String status = event.path("interaction").path("status").asText(event.path("status").asText());
                if (terminated.get()) return;
                emitter.send(SseEmitter.event().name(type).data(payload));
                if ("error".equals(type) || "interaction.failed".equals(type) || "interaction.cancelled".equals(type)
                        || "failed".equals(status) || "cancelled".equals(status)) {
                    if (terminated.compareAndSet(false, true)) emitter.complete();
                }
            } catch (IOException e) {
                log.warn("[LlmInteractions-stream] 解析或发送事件失败, platform={}", platform, e);
                if (terminated.compareAndSet(false, true)) emitter.completeWithError(e);
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                if (terminated.compareAndSet(false, true)) emitter.completeWithError(e);
                throw e;
            }
        };
    }

    private Consumer<String> buildResponsesSseConsumer(SseEmitter emitter, String platform, AtomicBoolean terminated) {
        return data -> {
            if (terminated.get()) return;
            if (data == null) {
                if (terminated.compareAndSet(false, true)) emitter.complete();
                return;
            }
            try {
                String payload = "[ERROR]".equals(data)
                        ? "{\"type\":\"error\",\"code\":\"" + ErrorCodeEnum.LLM_CALL_FAILED.getCode()
                                + "\",\"message\":\"LLM call failed\"}" : data;
                String type = JsonUtil.readTree(payload).path("type").asText();
                if (terminated.get()) return;
                emitter.send(SseEmitter.event().name(type).data(payload));
                if ("response.failed".equals(type) || "error".equals(type)) {
                    if (terminated.compareAndSet(false, true)) emitter.complete();
                }
            } catch (IOException e) {
                log.warn("[LlmResponses-stream] 解析或发送事件失败, platform={}", platform, e);
                if (terminated.compareAndSet(false, true)) emitter.completeWithError(e);
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                if (terminated.compareAndSet(false, true)) emitter.completeWithError(e);
                throw e;
            }
        };
    }

    private LlmResponsesRequest toResponsesRequest(LlmResponsesRequestVO vo) {
        return LlmResponsesRequest.builder()
                .apiKey(vo.getApiKey())
                .endpoint(vo.getEndpoint())
                .model(vo.getModel())
                .input(vo.getInput())
                .instructions(vo.getInstructions())
                .temperature(vo.getTemperature())
                .topP(vo.getTopP())
                .maxOutputTokens(vo.getMaxOutputTokens())
                .tools(vo.getTools())
                .toolChoice(vo.getToolChoice())
                .extraParams(vo.getExtraParams())
                .build();
    }

    private void checkPlatform(String platform) {
        if (!StringUtils.hasText(platform)) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "platform 不能为空");
        }
    }

    private Consumer<String> buildSseConsumer(SseEmitter emitter, String platform) {
        return chunk -> {
            if (chunk == null) {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                } catch (IOException e) {
                    log.warn("[LlmChat-stream] 发送 done 事件失败, platform={}", platform);
                }
                emitter.complete();
            } else if ("[ERROR]".equals(chunk)) {
                emitter.completeWithError(new RuntimeException("LLM stream error"));
            } else {
                try {
                    if (chunk.startsWith("[TEXT]")) {
                        emitter.send(SseEmitter.event().name("chunk").data(chunk.substring("[TEXT]".length())));
                    } else if (chunk.startsWith("[TOOL_CALLS]")) {
                        emitter.send(SseEmitter.event().name("tool_calls").data(chunk.substring("[TOOL_CALLS]".length())));
                    } else if (chunk.startsWith("[REASONING]")) {
                        emitter.send(SseEmitter.event().name("reasoning").data(chunk.substring("[REASONING]".length())));
                    } else if (chunk.startsWith("[THINKING_BLOCKS]")) {
                        emitter.send(SseEmitter.event().name("thinking_blocks").data(chunk.substring("[THINKING_BLOCKS]".length())));
                    } else {
                        emitter.send(SseEmitter.event().name("chunk").data(chunk));
                    }
                } catch (IOException e) {
                    log.warn("[LlmChat-stream] 客户端已断开, platform={}", platform);
                    emitter.completeWithError(e);
                }
            }
        };
    }

    static LlmRequest toServiceRequest(LlmRequestVO vo) {
        List<LlmMessage> messages = vo.getMessages() == null ? null : vo.getMessages().stream()
                .map(m -> {
                    List<MessageContent> contents = m.getContents() != null && !m.getContents().isEmpty()
                            ? m.getContents().stream()
                                    .map(content -> {
                                        MessageContent target = new MessageContent();
                                        BeanUtils.copyProperties(content, target);
                                        return target;
                                    })
                                    .collect(Collectors.toList())
                            : m.getValue() == null ? null : List.of(new MessageContent(
                                    m.getType() != null ? m.getType() : ContentTypeEnum.TEXT,
                                    m.getValue(), m.getDetail()));
                    return LlmMessage.builder()
                            .role(m.getRole())
                            .name(m.getName())
                            .audio(m.getAudio())
                            .toolCalls(m.getToolCalls())
                            .toolCallId(m.getToolCallId())
                            .reasoningContent(m.getReasoningContent())
                            .thinkingBlocks(m.getThinkingBlocks())
                            .contents(contents)
                            .build();
                })
                .collect(Collectors.toList());
        return LlmRequest.builder()
                .apiKey(vo.getApiKey())
                .endpoint(vo.getEndpoint())
                .modelCode(vo.getModelCode())
                .messages(messages)
                .model(vo.getModel())
                .maxOutputTokens(vo.getMaxOutputTokens())
                .instructions(vo.getInstructions())
                .generationConfig(vo.getGenerationConfig())
                .previousInteractionId(vo.getPreviousInteractionId())
                .continuationToken(vo.getContinuationToken())
                .store(vo.getStore())
                .background(vo.getBackground())
                .interactionSteps(vo.getInteractionSteps())
                .temperature(vo.getTemperature())
                .topP(vo.getTopP())
                .topK(vo.getTopK())
                .frequencyPenalty(vo.getFrequencyPenalty())
                .maxTokens(vo.getMaxTokens())
                .tools(vo.getTools())
                .toolChoice(vo.getToolChoice())
                .extraParams(vo.getExtraParams())
                .build();
    }

    private LlmResponseVO toVO(LlmResponse response) {
        return LlmResponseVO.builder()
                .requestId(response.getRequestId())
                .modelCode(response.getModelCode())
                .createdAt(response.getCreatedAt())
                .choices(response.getChoices())
                .status(response.getStatus())
                .maxOutputTokens(response.getMaxOutputTokens())
                .output(response.getOutput())
                .usage(response.getUsage())
                .extraData(response.getExtraData())
                .build();
    }
}

