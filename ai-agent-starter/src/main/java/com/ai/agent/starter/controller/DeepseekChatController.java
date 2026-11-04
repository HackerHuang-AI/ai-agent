package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.service.impl.DeepseekServiceImpl;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.vo.LlmRequestVO;
import com.ai.agent.starter.vo.LlmResponseVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.function.Consumer;
/**
 * Deepseek 平台对话接口
 *               凭证（apiKey / endpoint）由调用方通过请求体传入
 *
 *               POST /api/deepseek/chat        同步对话，等待完整响应后返回
 *               POST /api/deepseek/chat/stream  流式对话，SSE 实时推送 chunk
 *
 * @author HUANGcong
 * @since 2026-06-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/deepseek")
public class DeepseekChatController {

    private final DeepseekServiceImpl deepseekService;

    public DeepseekChatController(DeepseekServiceImpl deepseekService) {
        this.deepseekService = deepseekService;
    }

    /**
     * 同步对话接口
     * POST /api/deepseek/chat
     */
    @PostMapping("/chat")
    public Result<LlmResponseVO> chat(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        log.info("[Deepseek-chat] 开始处理, req={}", req);
        try {
            LlmResponse response = deepseekService.chat(LlmChatController.toServiceRequest(req));
            log.info("[Deepseek-chat] 处理完成, response={}", response);
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Deepseek-chat] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 流式对话接口，基于 SSE（Server-Sent Events）
     * POST /api/deepseek/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        log.info("[Deepseek-stream] 开始处理, req={}", req);
        SseEmitter emitter = new SseEmitter(0L);
        deepseekService.chatStream(LlmChatController.toServiceRequest(req), buildSseConsumer(emitter, req.getModelCode()));
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
                    log.warn("[Deepseek-stream] 客户端已断开, model={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
    }

    // ==================== 私有方法 ====================

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

