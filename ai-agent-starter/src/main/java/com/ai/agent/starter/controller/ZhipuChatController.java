package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.impl.ZhipuServiceImpl;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.LlmRequestVO;
import com.ai.agent.starter.controller.vo.LlmResponseVO;
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
 * @Description: 智谱 GLM 平台对话接口
 *               注意：apiKey 格式为 id.secret，服务层内部自动生成 JWT Token。
 *
 *               POST /api/zhipu/chat        同步对话
 *               POST /api/zhipu/chat/stream  流式对话，SSE 实时推送 chunk
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller
 * @ClassName: ZhipuChatController
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/zhipu")
public class ZhipuChatController {

    private final ZhipuServiceImpl zhipuService;

    public ZhipuChatController(ZhipuServiceImpl zhipuService) {
        this.zhipuService = zhipuService;
    }

    @PostMapping("/chat")
    public Result<LlmResponseVO> chat(@Valid @RequestBody LlmRequestVO req) {
        log.info("[Zhipu-chat] 开始处理, req={}", req);
        try {
            LlmResponse response = zhipuService.chat(toServiceRequest(req));
            log.info("[Zhipu-chat] 处理完成, response={}", response);
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Zhipu-chat] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody LlmRequestVO req) {
        log.info("[Zhipu-stream] 开始处理, req={}", req);
        SseEmitter emitter = new SseEmitter(0L);
        zhipuService.chatStream(toServiceRequest(req), buildSseConsumer(emitter, req.getModelCode()));
        return emitter;
    }

    private Consumer<String> buildSseConsumer(SseEmitter emitter, String tag) {
        return chunk -> {
            if (chunk == null) {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                } catch (IOException e) {
                    log.warn("[Zhipu-stream] 发送 done 事件失败, model={}", tag);
                }
                emitter.complete();
            } else if ("[ERROR]".equals(chunk)) {
                emitter.completeWithError(new RuntimeException("LLM stream error"));
            } else {
                try {
                    emitter.send(SseEmitter.event().name("chunk").data(chunk));
                } catch (IOException e) {
                    log.warn("[Zhipu-stream] 客户端已断开, model={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
    }

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
                .topK(vo.getTopK())
                .frequencyPenalty(vo.getFrequencyPenalty())
                .maxTokens(vo.getMaxTokens())
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

