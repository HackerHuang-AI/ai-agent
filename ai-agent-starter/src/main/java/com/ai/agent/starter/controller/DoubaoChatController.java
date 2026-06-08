package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentType;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.infrastructure.llm.service.DoubaoService;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.doubao.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * @Description: 豆包（火山方舟）平台对话接口
 *               凭证（apiKey / endpoint）由调用方通过请求体传入
 *
 *               POST /api/doubao/chat               同步对话（Chat Completions 协议，纯文本）
 *               POST /api/doubao/chat/stream         流式对话，SSE 实时推送 chunk
 *               POST /api/doubao/multimodal/chat     多模态对话（Responses API，支持图片+文本）
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
@RequiredArgsConstructor
public class DoubaoChatController {

    private final DoubaoService doubaoService;

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * 同步对话接口（Chat Completions 协议，纯文本）
     * POST /api/doubao/chat
     *
     * @param req 请求体，必填：apiKey、endpoint、endpointId、messages
     */
    @PostMapping("/chat")
    public Result<DoubaoResponse> chat(@Valid @RequestBody DoubaoRequest req) {
        log.info("[Doubao-chat] 开始处理, endpointId={}", req.getEndpointId());
        try {
            LlmResponse response = doubaoService.chat(toServiceRequest(req), req.getApiKey(), req.getEndpoint());
            log.info("[Doubao-chat] 处理完成, endpointId={}, inputTokens={}, outputTokens={}",
                    req.getEndpointId(), response.getInputTokens(), response.getOutputTokens());
            return Result.success(toVO(response, req.getEndpointId()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Doubao-chat] 系统异常, endpointId={}", req.getEndpointId(), e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 流式对话接口，基于 SSE（Server-Sent Events）
     * POST /llm/doubao/chat/stream
     *
     * SSE 事件格式：
     *   event: chunk  → data: 文本片段
     *   event: done   → data: [DONE]（流结束）
     *   event: error  → data: 错误信息
     *
     * @param req 请求体，必填：endpointId、messages
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody DoubaoRequest req) {
        log.info("[Doubao-stream] 开始处理, endpointId={}", req.getEndpointId());
        SseEmitter emitter = new SseEmitter(0L);
        LlmRequest request = toServiceRequest(req);

        streamExecutor.submit(() -> {
            try {
                doubaoService.chatStream(request, req.getApiKey(), req.getEndpoint(), chunk -> {
                    if (chunk == null) {
                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (IOException e) {
                            log.warn("[Doubao-stream] 发送 done 事件失败, endpointId={}", req.getEndpointId());
                        }
                        emitter.complete();
                    } else {
                        try {
                            emitter.send(SseEmitter.event().name("chunk").data(chunk));
                        } catch (IOException e) {
                            log.warn("[Doubao-stream] 客户端已断开, endpointId={}", req.getEndpointId());
                            emitter.completeWithError(e);
                        }
                    }
                });
            } catch (Exception e) {
                log.error("[Doubao-stream] 系统异常, endpointId={}", req.getEndpointId(), e);
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 多模态对话接口（Responses API）
     * POST /api/doubao/multimodal/chat
     *
     * 支持图片+文本混合输入，调用火山方舟 /v3/responses 协议
     */
    @PostMapping("/multimodal/chat")
    public Result<DoubaoMultimodalResponse> multimodalChat(@Valid @RequestBody DoubaoMultimodalRequest req) {
        log.info("[Doubao-multimodal] 开始处理, model={}", req.getModel());
        try {
            List<Map<String, Object>> input = buildMultimodalInput(req);
            LlmResponse response = doubaoService.multimodalChat(req.getModel(), input, req.getApiKey(), req.getEndpoint());
            log.info("[Doubao-multimodal] 处理完成, model={}, inputTokens={}, outputTokens={}",
                    req.getModel(), response.getInputTokens(), response.getOutputTokens());
            return Result.success(DoubaoMultimodalResponse.builder()
                    .content(response.getContent())
                    .model(req.getModel())
                    .inputTokens(response.getInputTokens())
                    .outputTokens(response.getOutputTokens())
                    .build());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Doubao-multimodal] 系统异常, model={}", req.getModel(), e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    // ==================== 私有方法 ====================

    private LlmRequest toServiceRequest(DoubaoRequest vo) {
        List<LlmMessage> messages = vo.getMessages().stream()
                .map(m -> LlmMessage.builder()
                        .role(m.getRole())
                        .contents(List.of(new MessageContent(ContentType.TEXT, m.getContent(), null)))
                        .build())
                .collect(Collectors.toList());
        return LlmRequest.builder()
                .modelCode(vo.getEndpointId())
                .messages(messages)
                .temperature(vo.getTemperature())
                .maxTokens(vo.getMaxTokens())
                .build();
    }

    /**
     * 将多模态 VO 转换为 Responses API 的 input 格式
     * input[].content[] 中 input_image 使用 image_url 字段
     */
    private List<Map<String, Object>> buildMultimodalInput(DoubaoMultimodalRequest req) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (DoubaoMultimodalMessageVO msg : req.getInput()) {
            List<Map<String, Object>> contentList = new ArrayList<>();
            for (var c : msg.getContent()) {
                Map<String, Object> item = new HashMap<>();
                item.put("type", c.getType());
                if ("input_text".equals(c.getType())) {
                    item.put("text", c.getText());
                } else if ("input_image".equals(c.getType())) {
                    item.put("image_url", c.getImageUrl());
                }
                contentList.add(item);
            }
            Map<String, Object> message = new HashMap<>();
            message.put("role", msg.getRole());
            message.put("content", contentList);
            input.add(message);
        }
        return input;
    }

    private DoubaoResponse toVO(LlmResponse response, String endpointId) {
        return DoubaoResponse.builder()
                .content(response.getContent())
                .endpointId(endpointId)
                .inputTokens(response.getInputTokens())
                .outputTokens(response.getOutputTokens())
                .finishReason(response.getFinishReason())
                .build();
    }
}

