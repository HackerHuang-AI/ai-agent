package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.deepseek.DeepseekRequest;
import com.ai.agent.starter.controller.vo.deepseek.DeepseekResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @Description: Deepseek 平台对话接口
 *               凭证（apiKey / endpoint）由调用方通过请求体传入
 *
 *               POST /api/deepseek/chat        同步对话，等待完整响应后返回
 *               POST /api/deepseek/chat/stream  流式对话，SSE 实时推送 chunk
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller
 * @ClassName: DeepseekChatController
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/deepseek")
public class DeepseekChatController {

    private final LlmService llmService;

    public DeepseekChatController(@Qualifier("deepseekServiceImpl") LlmService llmService) {
        this.llmService = llmService;
    }

    /**
     * 同步对话接口
     * POST /api/deepseek/chat
     */
    @PostMapping("/chat")
    public Result<DeepseekResponse> chat(@Valid @RequestBody DeepseekRequest req) {
        log.info("[Deepseek-chat] 开始处理, req={}", req);
        try {
            LlmResponse response = llmService.chat(toServiceRequest(req));
            log.info("[Deepseek-chat] 处理完成, model={}, inputTokens={}, outputTokens={}",
                    req.getModelCode(), response.getInputTokens(), response.getOutputTokens());
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Deepseek-chat] 系统异常, model={}", req.getModelCode(), e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 流式对话接口，基于 SSE（Server-Sent Events）
     * POST /api/deepseek/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody DeepseekRequest req) {
        log.info("[Deepseek-stream] 开始处理, model={}", req.getModelCode());
        SseEmitter emitter = new SseEmitter(0L);
        llmService.chatStream(toServiceRequest(req), buildSseConsumer(emitter, req.getModelCode()));
        return emitter;
    }

    private Consumer<String> buildSseConsumer(SseEmitter emitter, String tag) {
        return chunk -> {
            if (chunk == null) {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                } catch (IOException e) {
                    log.warn("[Deepseek-stream] 发送 done 事件失败, model={}", tag);
                }
                emitter.complete();
            } else {
                try {
                    emitter.send(SseEmitter.event().name("chunk").data(chunk));
                } catch (IOException e) {
                    log.warn("[Deepseek-stream] 客户端已断开, model={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
    }

    // ==================== 私有方法 ====================

    private LlmRequest toServiceRequest(DeepseekRequest vo) {
        List<LlmMessage> messages = vo.getMessages().stream()
                .map(m -> LlmMessage.builder()
                        .role(m.getRole())
                        .contents(List.of(new MessageContent(m.getType(), m.getValue(), m.getDetail())))
                        .build())
                .collect(Collectors.toList());
        return LlmRequest.builder()
                .apiKey(vo.getApiKey())
                .endpoint(vo.getEndpoint())
                .modelCode(vo.getModelCode())
                .messages(messages)
                .temperature(vo.getTemperature())
                .maxTokens(vo.getMaxTokens())
                .build();
    }

    private DeepseekResponse toVO(LlmResponse response) {
        return DeepseekResponse.builder()
                .content(response.getContent())
                .modelCode(response.getModelCode())
                .inputTokens(response.getInputTokens())
                .outputTokens(response.getOutputTokens())
                .finishReason(response.getFinishReason())
                .reasoningContent(response.getReasoningContent())
                .cacheHitTokens(response.getCacheHitTokens())
                .build();
    }
}

