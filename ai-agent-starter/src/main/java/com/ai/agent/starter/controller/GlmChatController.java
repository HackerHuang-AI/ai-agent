package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.service.impl.GlmServiceImpl;
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
 * 智谱 GLM 平台对话接口
 *               注意：apiKey 格式为 id.secret，服务层内部自动生成 JWT Token。
 *
 *               POST /api/glm/chat        同步对话
 *               POST /api/glm/chat/stream  流式对话，SSE 实时推送 chunk
 *
 * @author HUANGcong
 * @since 2026-06-28
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/glm")
public class GlmChatController {

    private final GlmServiceImpl glmService;

    public GlmChatController(GlmServiceImpl glmService) {
        this.glmService = glmService;
    }

    @PostMapping("/chat")
    public Result<LlmResponseVO> chat(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        log.info("[Glm-chat] 开始处理, req={}", req);
        try {
            LlmResponse response = glmService.chat(LlmChatController.toServiceRequest(req));
            log.info("[Glm-chat] 处理完成, response={}", response);
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Glm-chat] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        log.info("[Glm-stream] 开始处理, req={}", req);
        SseEmitter emitter = new SseEmitter(0L);
        glmService.chatStream(LlmChatController.toServiceRequest(req), buildSseConsumer(emitter, req.getModelCode()));
        return emitter;
    }

    private Consumer<String> buildSseConsumer(SseEmitter emitter, String tag) {
        return chunk -> {
            if (chunk == null) {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                } catch (IOException e) {
                    log.warn("[Glm-stream] 发送 done 事件失败, model={}", tag);
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
                    log.warn("[Glm-stream] 客户端已断开, model={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
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

