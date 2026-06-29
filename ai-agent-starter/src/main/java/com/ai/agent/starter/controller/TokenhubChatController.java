package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.LlmRequestVO;
import com.ai.agent.starter.controller.vo.LlmResponseVO;
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
 * @Description: Moonshot（Kimi）平台对话接口
 *
 *               POST /api/tokenhub/chat        同步对话
 *               POST /api/tokenhub/chat/stream  流式对话，SSE 实时推送 chunk
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller
 * @ClassName: TokenhubChatController
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/tokenhub")
public class TokenhubChatController {

    private final LlmService llmService;

    public TokenhubChatController(@Qualifier("tokenhubServiceImpl") LlmService llmService) {
        this.llmService = llmService;
    }

    @PostMapping("/chat")
    public Result<LlmResponseVO> chat(@Valid @RequestBody LlmRequestVO req) {
        log.info("[TokenHub-chat] 开始处理, req={}", req);
        try {
            LlmResponse response = llmService.chat(toServiceRequest(req));
            log.info("[TokenHub-chat] 处理完成, response={}", response);
            return Result.success(toVO(response));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[TokenHub-chat] 系统异常", e);
            throw new BizException(ErrorCodeEnum.SYSTEM_ERROR);
        }
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody LlmRequestVO req) {
        log.info("[TokenHub-stream] 开始处理, req={}", req);
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
                    log.warn("[TokenHub-stream] 发送 done 事件失败, model={}", tag);
                }
                emitter.complete();
            } else {
                try {
                    emitter.send(SseEmitter.event().name("chunk").data(chunk));
                } catch (IOException e) {
                    log.warn("[TokenHub-stream] 客户端已断开, model={}", tag);
                    emitter.completeWithError(e);
                }
            }
        };
    }

    private LlmRequest toServiceRequest(LlmRequestVO vo) {
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
                .topP(vo.getTopP())
                .maxTokens(vo.getMaxTokens())
                .extraParams(vo.getExtraParams())
                .build();
    }

    private LlmResponseVO toVO(LlmResponse response) {
        return LlmResponseVO.builder()
                .content(response.getContent())
                .modelCode(response.getModelCode())
                .inputTokens(response.getInputTokens())
                .outputTokens(response.getOutputTokens())
                .finishReason(response.getFinishReason())
                .extraData(response.getExtraData())
                .build();
    }
}

