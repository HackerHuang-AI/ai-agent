package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * @Description: Anthropic（Claude）平台 LLM 服务实现。
 *               与其他平台的关键差异：
 *               1. 认证：x-api-key Header（非 Authorization:Bearer），必须携带 anthropic-version
 *               2. Endpoint：/v1/messages（非 /v1/chat/completions）
 *               3. system 消息从 messages 中提取为顶层 system 字段；messages 只含 user/assistant
 *               4. max_tokens 必填
 *               5. 响应：content[0].text；token 字段 input_tokens/output_tokens
 *               6. 流式结束：event: message_stop（无 [DONE]）；文本 chunk 在 content_block_delta 事件
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: AnthropicServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/29
 * @Version: 1.0
 */
@Slf4j
@Service
public class AnthropicServiceImpl implements LlmService {

    private static final String ANTHROPIC_VERSION   = "2023-06-01";
    private static final String EVENT_MESSAGE_STOP  = "message_stop";
    private static final String EVENT_CONTENT_DELTA = "content_block_delta";
    private static final int    DEFAULT_MAX_TOKENS  = 4096;

    private static final MediaType    JSON        = MediaType.parse("application/json; charset=utf-8");
    
    private static final ObjectMapper MAPPER      = new ObjectMapper();


    private final ExecutorService streamExecutor;
    private final OkHttpConfig okHttpConfig;

    public AnthropicServiceImpl(@Qualifier("anthropicStreamExecutor") ExecutorService streamExecutor,
            OkHttpConfig okHttpConfig) {
        this.streamExecutor = streamExecutor;
        this.okHttpConfig = okHttpConfig;
    }

    // ==================== 同步对话 ====================

    @Override
    public LlmResponse chat(LlmRequest request) {
        log.info("[Anthropic-chat] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();
        try {
            Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
            try (Response response = okHttpConfig.getLlmClient().newCall(okRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String platformErr = extractErrorMessage(responseBody);
                    if (response.code() >= 400 && response.code() < 500) {
                        log.error("[Anthropic-chat] HTTP {}，不可重试, platformError={}", response.code(), platformErr);
                        throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED, platformErr);
                    }
                    log.warn("[Anthropic-chat] HTTP {} 失败, platformError={}", response.code(), platformErr);
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED, platformErr);
                }
                if (responseBody.isEmpty()) {
                    log.error("[Anthropic-chat] 响应体为空");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                LlmResponse result = parseResponse(responseBody, request.getModelCode());
                log.info("[Anthropic-chat] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                                request.getModelCode(), result.getInputTokens(), result.getOutputTokens(),
                                System.currentTimeMillis() - start);
                return result;
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Anthropic-chat] IO 异常", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    // ==================== 流式对话 ====================

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        String requestBody = buildRequestBody(request, true);
        log.info("[Anthropic-stream] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        try {
            streamExecutor.submit(() -> {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                try {
                    Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
                    try (Response response = okHttpConfig.getLlmClient().newCall(okRequest).execute()) {
                        if (!response.isSuccessful() || response.body() == null) {
                            String errBody = response.body() != null ? response.body().string() : "";
                            log.error("[Anthropic-stream] HTTP 失败, code={}, error={}",
                                    response.code(), extractErrorMessage(errBody));
                            chunkConsumer.accept("[ERROR]");
                            return;
                        }
                        parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                    }
                } catch (BizException e) {
                    log.error("[Anthropic-stream] 业务异常", e);
                    chunkConsumer.accept("[ERROR]");
                } catch (IOException e) {
                    log.error("[Anthropic-stream] IO 异常", e);
                    chunkConsumer.accept("[ERROR]");
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Anthropic-stream] 线程池已满，拒绝请求", e);
            chunkConsumer.accept("[ERROR]");
        }
    }

    // ==================== 请求构建 ====================

    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModelCode());
        body.put("stream", stream);
        // Anthropic max_tokens 必填
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : DEFAULT_MAX_TOKENS);

        // 提取 system 消息为顶层字段；messages 只保留 user/assistant
        String systemPrompt = null;
        List<Object> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            if ("system".equalsIgnoreCase(msg.getRole())) {
                systemPrompt = msg.isTextOnly() ? msg.getTextContent() : "";
            } else {
                Map<String, Object> m = new HashMap<>();
                m.put("role", msg.getRole());
                if (msg.isTextOnly()) {
                    m.put("content", msg.getTextContent());
                } else {
                    m.put("content", buildContentArray(msg));
                }
                messages.add(m);
            }
        }
        if (systemPrompt != null) body.put("system", systemPrompt);
        body.put("messages", messages);

        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        if (request.getExtraParams() != null) body.putAll(request.getExtraParams());

        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    private List<Map<String, Object>> buildContentArray(LlmMessage msg) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (MessageContent c : msg.getContents()) {
            switch (c.getType()) {
                case TEXT -> parts.add(Map.of("type", "text", "text", c.getValue()));
                case IMAGE -> {
                    String value = c.getValue();
                    Map<String, Object> source = new HashMap<>();
                    if (value.startsWith("data:")) {
                        int semiColon = value.indexOf(';');
                        int comma = value.indexOf(',');
                        source.put("type", "base64");
                        source.put("media_type", value.substring(5, semiColon));
                        source.put("data", comma >= 0 ? value.substring(comma + 1) : "");
                    } else {
                        source.put("type", "url");
                        source.put("url", value);
                    }
                    Map<String, Object> part = new HashMap<>();
                    part.put("type", "image");
                    part.put("source", source);
                    parts.add(part);
                }
                default -> throw new BizException(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
            }
        }
        return parts;
    }

    /** Anthropic 认证：x-api-key + anthropic-version（非 Authorization: Bearer） */
    private Request buildOkRequest(String endpoint, String apiKey, String requestBody) {
        return new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(requestBody, JSON))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .build();
    }

    // ==================== 响应解析 ====================

    /** Anthropic 响应结构：content[0].text，token 字段 input_tokens/output_tokens */
    private LlmResponse parseResponse(String responseJson, String modelCode) {
        try {
            JsonNode root     = MAPPER.readTree(responseJson);
            String content    = root.path("content").path(0).path("text").asText("");
            String stopReason = root.path("stop_reason").asText("");
            String finish     = "end_turn".equals(stopReason) ? "stop" : stopReason;
            JsonNode usage    = root.path("usage");
            return LlmResponse.builder()
                    .content(content)
                    .modelCode(modelCode)
                    .inputTokens(usage.path("input_tokens").asInt(0))
                    .outputTokens(usage.path("output_tokens").asInt(0))
                    .finishReason(finish)
                    .build();
        } catch (IOException e) {
            log.error("[Anthropic-chat] 响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    /**
     * Anthropic 流式格式：每行可能是 event: xxx 或 data: {...}。
     * 文本 chunk 在 event: content_block_delta 的 data.delta.text。
     * 流结束标志：event: message_stop（无 [DONE]）。
     */
    private void parseStreamResponse(ResponseBody responseBody, String modelCode, Consumer<String> chunkConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                    if (EVENT_MESSAGE_STOP.equals(currentEvent)) {
                        chunkConsumer.accept(null);
                        return;
                    }
                } else if (line.startsWith("data: ") && EVENT_CONTENT_DELTA.equals(currentEvent)) {
                    try {
                        String chunk = MAPPER.readTree(line.substring(6))
                                .path("delta").path("text").asText("");
                        if (!chunk.isEmpty()) chunkConsumer.accept(chunk);
                    } catch (IOException e) {
                        log.warn("[Anthropic-stream] chunk 解析失败，跳过");
                    }
                }
            }
            // 读完未收到 message_stop，主动结束
            chunkConsumer.accept(null);
        } catch (IOException e) {
            log.error("[Anthropic-stream] 流式响应解析失败, model={}", modelCode, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    // ==================== 工具方法 ====================

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            String msg = root.path("error").path("message").asText("");
            return msg.isEmpty() ? truncate(responseBody) : msg;
        } catch (Exception e) {
            return truncate(responseBody);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }


}

