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
import com.ai.agent.starter.controller.vo.doubao.DoubaoRequest;
import com.ai.agent.starter.controller.vo.doubao.DoubaoResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * @Description: 豆包（火山方舟）平台对话接口（第一阶段：凭证来自配置文件，不读 DB）
 *               后续凭证迁移至 DB 后，统一走 LlmChatController，此 Controller 删除。
 *
 *               POST /llm/doubao/chat        同步对话，等待完整响应后返回
 *               POST /llm/doubao/chat/stream  流式对话，SSE 实时推送 chunk
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
@RequestMapping("/llm/doubao")
@RequiredArgsConstructor
public class DoubaoChatController {

    private final DoubaoService doubaoService;

    @Value("${llm.doubao.api-key}")
    private String apiKey;

    @Value("${llm.doubao.endpoint}")
    private String endpoint;

    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    /**
     * 同步对话接口
     * POST /llm/doubao/chat
     *
     * @param req 请求体，必填：endpointId（火山方舟模型接入点 ID）、messages
     */
    @PostMapping("/chat")
    public Result<DoubaoResponse> chat(@Valid @RequestBody DoubaoRequest req) {
        log.info("[Doubao-chat] 开始处理, endpointId={}", req.getEndpointId());
        try {
            checkApiKey();
            LlmResponse response = doubaoService.chat(toServiceRequest(req), apiKey, endpoint);
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
        checkApiKey();
        SseEmitter emitter = new SseEmitter(0L);
        LlmRequest request = toServiceRequest(req);

        streamExecutor.submit(() -> {
            try {
                doubaoService.chatStream(request, apiKey, endpoint, chunk -> {
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

    // ==================== 私有方法 ====================

    private void checkApiKey() {
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
    }

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

