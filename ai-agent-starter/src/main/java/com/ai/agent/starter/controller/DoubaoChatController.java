package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmModelPage;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.LlmResponsesRequest;
import com.ai.agent.application.service.impl.DoubaoServiceImpl;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.vo.LlmCredentialVO;
import com.ai.agent.starter.vo.LlmRequestVO;
import com.ai.agent.starter.vo.LlmResponseVO;
import com.ai.agent.starter.vo.LlmResponsesRequestVO;
import jakarta.validation.Valid;
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
 * 豆包（火山方舟）平台对话接口
 *               凭证（apiKey / endpoint）由调用方通过请求体传入，或从 Nacos 兜底。
 *
 *               POST /api/doubao/chat                    同步对话（Chat Completions 协议，文本和图文输入）
 *               POST /api/doubao/chat/stream              流式对话，SSE 实时推送 chunk
 *
 * @author HUANGcong
 * @since 2026-06-04
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/doubao")
public class DoubaoChatController {

    private final DoubaoServiceImpl doubaoService;

    public DoubaoChatController(DoubaoServiceImpl doubaoService) {
        this.doubaoService = doubaoService;
    }

    /**
     * 查询豆包平台支持的模型列表
     * POST /api/doubao/models
     *
     * <p>apiKey / endpoint 可选，为空时从 Nacos ai-agent-doubao.json chat 块兜底。
     * 若传入则使用调用方自定义凭证，适用于多租户场景。
     */
    @PostMapping("/models")
    public Result<LlmModelPage> listModels(@Valid @RequestBody(required = false) LlmCredentialVO req) {
        if (req == null) req = new LlmCredentialVO();
        String apiKey = req.getApiKey();
        int pageNo = req.getPageNo();
        int pageSize = req.getPageSize();
        log.info("[Doubao-models] 开始查询模型列表, pageNo={}, pageSize={}, apiKey={}", pageNo, pageSize, apiKey != null ? "已传" : "Nacos兜底");
        try {
            LlmModelPage models = doubaoService.listModels(apiKey, pageNo, pageSize);
            log.info("[Doubao-models] 查询成功, total={}", models.getTotal());
            return Result.success(models);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Doubao-models] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 同步对话接口（Chat Completions 协议，支持文本和图文输入）
     * POST /api/doubao/chat
     */
    @PostMapping("/chat")
    public Result<LlmResponseVO> chat(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        log.info("[Doubao-chat] 开始处理, req={}", req);
        try {
            LlmResponse response = doubaoService.chat(LlmChatController.toServiceRequest(req));
            log.info("[Doubao-chat] 处理完成, response={}", response);
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Doubao-chat] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 流式对话接口，基于 SSE（Server-Sent Events）
     * POST /api/doubao/chat/stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Validated(LlmRequestVO.Chat.class) @RequestBody LlmRequestVO req) {
        log.info("[Doubao-stream] 开始处理, req={}", req);
        SseEmitter emitter = new SseEmitter(0L);
        doubaoService.chatStream(LlmChatController.toServiceRequest(req), buildSseConsumer(emitter, req.getModelCode()));
        return emitter;
    }

    @PostMapping("/responses")
    public Result<LlmResponseVO> responses(@Valid @RequestBody LlmResponsesRequestVO req) {
        LlmResponse response = doubaoService.responses(LlmResponsesRequest.builder()
                .apiKey(req.getApiKey()).endpoint(req.getEndpoint()).model(req.getModel()).input(req.getInput())
                .instructions(req.getInstructions()).temperature(req.getTemperature()).topP(req.getTopP())
                .maxOutputTokens(req.getMaxOutputTokens()).tools(req.getTools()).toolChoice(req.getToolChoice())
                .extraParams(req.getExtraParams()).build());
        return Result.success(toVO(response));
    }

    private Consumer<String> buildSseConsumer(SseEmitter emitter, String tag) {
        return chunk -> {
            if (chunk == null) {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                } catch (IOException e) {
                    log.warn("[Doubao-stream] 发送 done 事件失败, modelCode={}", tag);
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
                    log.warn("[Doubao-stream] 客户端已断开, modelCode={}", tag);
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

