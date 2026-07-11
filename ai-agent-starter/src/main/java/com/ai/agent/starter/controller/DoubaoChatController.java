package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.impl.DoubaoServiceImpl;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.LlmRequestVO;
import com.ai.agent.starter.controller.vo.LlmResponseVO;
import com.ai.agent.starter.controller.vo.MessageContentVO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @Description: 豆包（火山方舟）平台对话接口
 *               凭证（apiKey / endpoint）由调用方通过请求体传入，或从 Nacos 兜底。
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
                    emitter.send(SseEmitter.event().name("chunk").data(chunk));
                } catch (IOException e) {
                    log.warn("[Doubao-stream] 客户端已断开, modelCode={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
    }

    /**
     * 多模态对话接口（Responses API，支持图片URL或Base64）
     * POST /api/doubao/multimodal/chat
     *
     * <p>入参复用 {@link LlmRequestVO}，messages 中每条用 contents 字段描述多内容块：
     * <pre>{@code
     * {
     *   "messages": [{
     *     "role": "user",
     *     "contents": [
     *       {"type": "IMAGE", "value": "https://example.com/img.jpg"},
     *       {"type": "TEXT",  "value": "描述这张图片"}
     *     ]
     *   }]
     * }
     * }</pre>
     */
    @PostMapping("/multimodal/chat")
    public Result<LlmResponseVO> multimodalChat(@Valid @RequestBody LlmRequestVO req) {
        log.info("[Doubao-multimodal] 开始处理, req={}", req);
        try {
            List<Map<String, Object>> input = toMultimodalInput(req);
            LlmResponse response = doubaoService.multimodalChat(req.getModelCode(), input, req.getApiKey(), req.getEndpoint());
            log.info("[Doubao-multimodal] 处理完成, response={}", response);
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Doubao-multimodal] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    /**
     * 多模态对话接口（form-data 上传本地图片）
     * POST /api/doubao/multimodal/chat/file
     *
     * <p>form-data 参数：
     * <ul>
     *   <li>image  - 图片文件</li>
     *   <li>text   - 对图片的提问文字</li>
     *   <li>apiKey / endpoint / model - 可选，为空从 Nacos 兜底</li>
     * </ul>
     */
    @PostMapping("/multimodal/chat/file")
    public Result<LlmResponseVO> multimodalChatFile(
            @RequestParam("image") MultipartFile image,
            @RequestParam("text") String text,
            @RequestParam(value = "apiKey",   required = false) String apiKey,
            @RequestParam(value = "endpoint", required = false) String endpoint,
            @RequestParam(value = "model",    required = false) String model) {
        log.info("[Doubao-multimodal-file] 开始处理, model={}, fileName={}", model, image.getOriginalFilename());
        try {
            if (image.isEmpty()) {
                throw new BizException(ErrorCodeEnum.IMAGE_FILE_NOT_FOUND);
            }
            String mimeType = image.getContentType() != null ? image.getContentType() : "image/jpeg";
            LlmResponse response = doubaoService.multimodalChatFile(
                    image.getBytes(), mimeType, text, model, apiKey, endpoint);
            log.info("[Doubao-multimodal-file] 处理完成, response={}", response);
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Doubao-multimodal-file] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    // ==================== 私有方法 ====================

    /** 非多模态接口：LlmRequestVO → LlmRequest（单内容块，type + value） */
    private LlmRequest toServiceRequest(LlmRequestVO vo) {
        List<LlmMessage> messages = vo.getMessages().stream()
                .map(m -> LlmMessage.builder()
                        .role(m.getRole())
                        .contents(List.of(new MessageContent(m.getType() != null ? m.getType() : ContentTypeEnum.TEXT, m.getValue(), m.getDetail())))
                        .build())
                .collect(Collectors.toList());
        return LlmRequest.builder()
                .apiKey(vo.getApiKey())
                .endpoint(vo.getEndpoint())
                .modelCode(vo.getModelCode())
                .messages(messages)
                .temperature(vo.getTemperature())
                .topP(vo.getTopP())
                .maxTokens(vo.getMaxTokens())
                .extraParams(vo.getExtraParams())
                .build();
    }

    /**
     * 多模态接口：LlmRequestVO.messages（contents 字段）→ 豆包 Responses API input 结构。
     * 每条 message 的 contents 列表映射为 content 数组：
     *   IMAGE → {type: "input_image", image_url: value}
     *   TEXT  → {type: "input_text",  text: value}
     * 其余类型（FILE/VIDEO）豆包 Responses API 暂不支持，直接拒绝。
     */
    private List<Map<String, Object>> toMultimodalInput(LlmRequestVO vo) {
        if (CollectionUtils.isEmpty(vo.getMessages())) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "messages 不能为空");
        }
        return vo.getMessages().stream().map(msg -> {
            if (CollectionUtils.isEmpty(msg.getContents())) {
                throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "多模态消息的 contents 不能为空");
            }
            List<Map<String, Object>> contentList = msg.getContents().stream()
                    .map(this::toDoubaoContent)
                    .collect(Collectors.toList());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", msg.getRole());
            message.put("content", contentList);
            return message;
        }).collect(Collectors.toList());
    }

    /** 单个内容块 VO → 豆包 Responses API content 元素 */
    private Map<String, Object> toDoubaoContent(MessageContentVO c) {
        if (c.getType() == null) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "contents 中 type 不能为空");
        }
        if (c.getValue() == null || c.getValue().isBlank()) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "contents 中 value 不能为空");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        switch (c.getType()) {
            case IMAGE -> { item.put("type", "input_image"); item.put("image_url", c.getValue()); }
            case TEXT  -> { item.put("type", "input_text");  item.put("text", c.getValue()); }
            default    -> throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL,
                    "豆包多模态暂不支持 " + c.getType() + " 类型");
        }
        return item;
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

