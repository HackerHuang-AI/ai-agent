package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.impl.DoubaoServiceImpl;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.LlmCredentialVO;
import com.ai.agent.starter.controller.vo.LlmRequestVO;
import com.ai.agent.starter.controller.vo.LlmResponseVO;
import com.ai.agent.starter.controller.vo.LlmResponsesRequestVO;
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
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @Description: 豆包（火山方舟）平台对话接口
 *               凭证（apiKey / endpoint）由调用方通过请求体传入，或从 Nacos 兜底。
 *
 *               POST /api/doubao/chat                    同步对话（Chat Completions 协议，文本和图文输入）
 *               POST /api/doubao/chat/stream              流式对话，SSE 实时推送 chunk
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller
 * @ClassName: DoubaoChatController
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
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
    public Result<LlmModelPage> listModels(@RequestBody(required = false) LlmCredentialVO req) {
        String apiKey = req != null ? req.getApiKey() : null;
        int pageNo = req != null && req.getPageNo() != null ? req.getPageNo() : 1;
        int pageSize = req != null && req.getPageSize() != null ? req.getPageSize() : 20;
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
    public Result<LlmResponseVO> chat(@Valid @RequestBody LlmRequestVO req) {
        log.info("[Doubao-chat] 开始处理, req={}", req);
        try {
            LlmResponse response = doubaoService.chat(toServiceRequest(req));
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
    public SseEmitter chatStream(@Valid @RequestBody LlmRequestVO req) {
        log.info("[Doubao-stream] 开始处理, req={}", req);
        SseEmitter emitter = new SseEmitter(0L);
        doubaoService.chatStream(toServiceRequest(req), buildSseConsumer(emitter, req.getModelCode()));
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
            } else if (!chunk.startsWith("[TOOL_CALLS]")) {
                try {
                    emitter.send(SseEmitter.event().name("chunk").data(chunk));
                } catch (IOException e) {
                    log.warn("[Doubao-stream] 客户端已断开, modelCode={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
    }

    // ==================== 私有方法 ====================

    /** LlmRequestVO → LlmRequest，支持 type/value 或 contents 图文内容块。 */
    private LlmRequest toServiceRequest(LlmRequestVO vo) {
        List<LlmMessage> messages = vo.getMessages().stream()
                .map(m -> {
                    if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                        return LlmMessage.ofToolCalls(m.getToolCalls(), m.getValue());
                    }
                    if (m.getToolCallId() != null) {
                        return LlmMessage.ofToolResult(m.getToolCallId(), m.getValue());
                    }
                    List<MessageContent> contents = m.getContents() != null && !m.getContents().isEmpty()
                            ? m.getContents().stream()
                                    .map(content -> new MessageContent(content.getType(), content.getValue(), content.getDetail()))
                                    .collect(Collectors.toList())
                            : List.of(new MessageContent(
                                    m.getType() != null ? m.getType() : ContentTypeEnum.TEXT,
                                    m.getValue(), m.getDetail()));
                    return LlmMessage.builder()
                            .role(m.getRole())
                            .contents(contents)
                            .build();
                })
                .collect(Collectors.toList());
        return LlmRequest.builder()
                .apiKey(vo.getApiKey())
                .endpoint(vo.getEndpoint())
                .modelCode(vo.getModelCode())
                .messages(messages)
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

