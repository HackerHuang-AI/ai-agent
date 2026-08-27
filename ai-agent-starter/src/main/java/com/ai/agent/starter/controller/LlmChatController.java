package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.LlmCredentialVO;
import com.ai.agent.starter.controller.vo.LlmRequestVO;
import com.ai.agent.starter.controller.vo.LlmResponseVO;
import com.ai.agent.starter.controller.vo.LlmResponsesRequestVO;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * @Description: LLM 统一对话接口，通过 platform 参数路由到对应平台实现。
 *
 *               POST /api/llm/chat              同步对话（文本和图文输入）
 *               POST /api/llm/chat/stream        流式对话（文本和图文输入）
 *               POST /api/llm/responses          Responses API 调用
 *               POST /api/llm/models             模型列表
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller
 * @ClassName: LlmChatController
 * @Author: HUANGcong
 * @Date: Created in 2026/7/16
 * @Version: 1.0
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/llm")
public class LlmChatController {

    private final LlmRouter llmRouter;

    public LlmChatController(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    @PostMapping("/models")
    public Result<LlmModelPage> listModels(@RequestBody LlmCredentialVO req) {
        checkPlatform(req.getPlatform());
        return Result.success(llmRouter.listModels(req.getPlatform(), req.getApiKey(),
                req.getPageNo() != null ? req.getPageNo() : 1,
                req.getPageSize() != null ? req.getPageSize() : 20));
    }

    @PostMapping("/chat")
    public Result<LlmResponseVO> chat(@Valid @RequestBody LlmRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmChat] 开始处理, platform={}, req={}", req.getPlatform(), req);
        LlmResponse response = llmRouter.chat(req.getPlatform(), toServiceRequest(req));
        log.info("[LlmChat] 处理完成, platform={}", req.getPlatform());
        return Result.success(toVO(response));
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody LlmRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmChat-stream] 开始处理, platform={}, req={}", req.getPlatform(), req);
        SseEmitter emitter = new SseEmitter(0L);
        llmRouter.chatStream(req.getPlatform(), toServiceRequest(req), buildSseConsumer(emitter, req.getPlatform()));
        return emitter;
    }

    @PostMapping("/responses")
    public Result<LlmResponseVO> responses(@Valid @RequestBody LlmResponsesRequestVO req) {
        checkPlatform(req.getPlatform());
        log.info("[LlmResponses] 开始处理, platform={}, model={}", req.getPlatform(), req.getModel());
        LlmResponse response = llmRouter.responses(req.getPlatform(), LlmResponsesRequest.builder()
                .apiKey(req.getApiKey())
                .endpoint(req.getEndpoint())
                .model(req.getModel())
                .input(req.getInput())
                .instructions(req.getInstructions())
                .temperature(req.getTemperature())
                .topP(req.getTopP())
                .maxOutputTokens(req.getMaxOutputTokens())
                .tools(req.getTools())
                .toolChoice(req.getToolChoice())
                .extraParams(req.getExtraParams())
                .build());
        log.info("[LlmResponses] 处理完成, platform={}", req.getPlatform());
        return Result.success(toVO(response));
    }

    private void checkPlatform(String platform) {
        if (!StringUtils.hasText(platform)) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "platform 不能为空");
        }
    }

    private Consumer<String> buildSseConsumer(SseEmitter emitter, String platform) {
        return chunk -> {
            if (chunk == null) {
                try {
                    emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                } catch (IOException e) {
                    log.warn("[LlmChat-stream] 发送 done 事件失败, platform={}", platform);
                }
                emitter.complete();
            } else if ("[ERROR]".equals(chunk)) {
                emitter.completeWithError(new RuntimeException("LLM stream error"));
            } else if (!chunk.startsWith("[TOOL_CALLS]")) {
                try {
                    emitter.send(SseEmitter.event().name("chunk").data(chunk));
                } catch (IOException e) {
                    log.warn("[LlmChat-stream] 客户端已断开, platform={}", platform);
                    emitter.completeWithError(e);
                }
            }
        };
    }

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

