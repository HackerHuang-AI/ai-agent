package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentType;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.impl.DoubaoServiceImpl;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.doubao.*;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @Description: 豆包（火山方舟）平台对话接口
 *               凭证（apiKey / endpoint）由调用方通过请求体传入
 *
 *               POST /api/doubao/chat                    同步对话（Chat Completions 协议，纯文本）
 *               POST /api/doubao/chat/stream              流式对话，SSE 实时推送 chunk
 *               POST /api/doubao/multimodal/chat          多模态对话（Responses API，image_url 或 Base64）
 *               POST /api/doubao/multimodal/chat/file     多模态对话（form-data，上传本地图片）
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
     * 同步对话接口（Chat Completions 协议，纯文本）
     * POST /api/doubao/chat
     */
    @PostMapping("/chat")
    public Result<DoubaoResponse> chat(@Valid @RequestBody DoubaoRequest req) {
        log.info("[Doubao-chat] 开始处理, DoubaoRequest={}", req);
        try {
            LlmResponse response = doubaoService.chat(toServiceRequest(req));
            log.info("[Doubao-chat] 处理完成, response={}", response);
            return Result.success(toVO(response, req.getEndpointId()));
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
    public SseEmitter chatStream(@Valid @RequestBody DoubaoRequest req) {
        log.info("[Doubao-stream] 开始处理, DoubaoRequest={}", req);
        SseEmitter emitter = new SseEmitter(0L);
        doubaoService.chatStream(toServiceRequest(req), buildSseConsumer(emitter, req.getEndpointId()));
        return emitter;
    }

    private Consumer<String> buildSseConsumer(SseEmitter emitter, String tag) {
        return chunk -> {
            if (chunk == null) {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                } catch (IOException e) {
                    log.warn("[Doubao-stream] 发送 done 事件失败, endpointId={}", tag);
                }
                emitter.complete();
            } else {
                try {
                    emitter.send(SseEmitter.event().name("chunk").data(chunk));
                } catch (IOException e) {
                    log.warn("[Doubao-stream] 客户端已断开, endpointId={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
    }

    /**
     * 多模态对话接口（Responses API）
     * POST /api/doubao/multimodal/chat
     */
    @PostMapping("/multimodal/chat")
    public Result<DoubaoMultimodalResponse> multimodalChat(@Valid @RequestBody DoubaoMultimodalRequest req) {
        log.info("[Doubao-multimodal] 开始处理, DoubaoMultimodalRequest={}", req);
        try {
            List<Map<String, Object>> input = buildMultimodalInput(req);
            LlmResponse response = doubaoService.multimodalChat(req.getModel(), input, req.getApiKey(), req.getEndpoint());
            log.info("[Doubao-multimodal] 处理完成, response={}", response);
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

    /**
     * 多模态对话接口（form-data 上传本地图片）
     * POST /api/doubao/multimodal/chat/file
     *
     * form-data 字段：
     *   image  → 图片文件（必填）
     *   apiKey → API Key（必填）
     *   endpoint → Responses API 地址（必填）
     *   model  → 模型接入点 ID（必填）
     *   text   → 提问文本（必填）
     */
    @PostMapping("/multimodal/chat/file")
    public Result<DoubaoMultimodalResponse> multimodalChatFile(
            @RequestParam("image") MultipartFile image,
            @RequestParam("apiKey") String apiKey,
            @RequestParam("endpoint") String endpoint,
            @RequestParam("model") String model,
            @RequestParam("text") String text) {
        log.info("[Doubao-multimodal-file] 开始处理, model={}, fileName={}", model, image.getOriginalFilename());
        try {
            if (image.isEmpty()) {
                throw new BizException(ErrorCodeEnum.IMAGE_FILE_NOT_FOUND);
            }
            String mimeType = image.getContentType() != null ? image.getContentType() : "image/jpeg";
            LlmResponse response = doubaoService.multimodalChatFile(
                    image.getBytes(), mimeType, text, model, apiKey, endpoint);
            log.info("[Doubao-multimodal-file] 处理完成, response={}", response);
            return Result.success(DoubaoMultimodalResponse.builder()
                    .content(response.getContent())
                    .model(model)
                    .inputTokens(response.getInputTokens())
                    .outputTokens(response.getOutputTokens())
                    .build());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Doubao-multimodal-file] 系统异常", e);
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
                .apiKey(vo.getApiKey())
                .endpoint(vo.getEndpoint())
                .modelCode(vo.getEndpointId())
                .messages(messages)
                .temperature(vo.getTemperature())
                .maxTokens(vo.getMaxTokens())
                .build();
    }

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

