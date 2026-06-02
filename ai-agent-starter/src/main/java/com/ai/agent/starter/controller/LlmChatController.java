package com.ai.agent.starter.controller;

import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.starter.common.Result;
import com.ai.agent.starter.controller.vo.ChatMessageVO;
import com.ai.agent.starter.controller.vo.ChatRequest;
import com.ai.agent.starter.controller.vo.ChatResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * @Description: LLM 对话接口，统一接入所有平台，屏蔽协议差异
 *
 *               POST /llm/chat         同步对话，等待完整响应后返回
 *               POST /llm/chat/stream  流式对话，SSE 实时推送 chunk
 *
 *               用户只需传：modelCode + messages（文本/图片/文件/视频）
 *               apiKey、endpoint、platformCode 全部由服务端从 DB 取，不对外暴露
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller
 * @ClassName: LlmChatController
 * @Author: HUANGcong
 * @Date: Created in 2026/6/2
 * @Version: 1.0
 */
@Slf4j
@RestController
@RequestMapping("/llm")
@RequiredArgsConstructor
public class LlmChatController {

    private final LlmService llmService;

    // 流式接口专用线程池，避免阻塞 Tomcat 线程
    private final ExecutorService streamExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 同步对话接口
     * 适用场景：需要完整回复后再处理，或不支持 SSE 的客户端
     *
     * @param chatRequest 对话请求，包含 modelCode 和 messages
     * @return 完整的模型回复
     */
    @PostMapping("/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest chatRequest) {
        LlmRequest request = toServiceRequest(chatRequest);
        LlmResponse response = llmService.chat(request);
        return Result.success(toVO(response));
    }

    /**
     * 流式对话接口，基于 SSE（Server-Sent Events）
     * 适用场景：需要实时显示模型逐字输出的场景
     *
     * 客户端使用示例（JS）：
     *   const es = new EventSource('/ai-agent/llm/chat/stream');
     *   或通过 POST + fetch 读取 ReadableStream
     *
     * SSE 事件格式：
     *   event: chunk          → data: 文本片段
     *   event: done           → data: [DONE]（流结束）
     *   event: error          → data: 错误信息
     *
     * @param chatRequest 对话请求
     * @return SSE 流
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest chatRequest) {
        // timeout=0 表示不超时，由 LLM 调用本身的 readTimeout(120s) 控制
        SseEmitter emitter = new SseEmitter(0L);

        LlmRequest request = toServiceRequest(chatRequest);

        streamExecutor.submit(() -> {
            try {
                llmService.chatStream(request, chunk -> {
                    if (chunk == null) {
                        // 流结束
                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (IOException e) {
                            log.warn("SSE 发送 done 事件失败, model={}", chatRequest.getModelCode());
                        }
                        emitter.complete();
                    } else {
                        try {
                            emitter.send(SseEmitter.event().name("chunk").data(chunk));
                        } catch (IOException e) {
                            // 客户端断开，中止流
                            log.warn("SSE 客户端已断开, model={}", chatRequest.getModelCode());
                            emitter.completeWithError(e);
                        }
                    }
                });
            } catch (Exception e) {
                log.error("SSE 流式调用异常, model={}, error={}", chatRequest.getModelCode(), e.getMessage(), e);
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

    /**
     * 将 Controller VO 转换为 Service 层入参
     */
    private LlmRequest toServiceRequest(ChatRequest vo) {
        List<LlmMessage> messages = vo.getMessages().stream()
                .map(this::toMessage)
                .collect(java.util.stream.Collectors.toList());

        return LlmRequest.builder()
                .modelCode(vo.getModelCode())
                .messages(messages)
                .temperature(vo.getTemperature())
                .maxTokens(vo.getMaxTokens())
                .build();
    }

    private LlmMessage toMessage(ChatMessageVO vo) {
        MessageContent content = new MessageContent(vo.getType(), vo.getValue(), vo.getDetail());
        return LlmMessage.builder()
                .role(vo.getRole())
                .contents(List.of(content))
                .build();
    }

    /**
     * 将 Service 层响应转换为 Controller VO
     */
    private ChatResponse toVO(LlmResponse response) {
        return ChatResponse.builder()
                .content(response.getContent())
                .modelCode(response.getModelCode())
                .inputTokens(response.getInputTokens())
                .outputTokens(response.getOutputTokens())
                .finishReason(response.getFinishReason())
                .reasoningContent(response.getReasoningContent())
                .build();
    }
}

