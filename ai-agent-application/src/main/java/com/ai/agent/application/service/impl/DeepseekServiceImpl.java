package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.ai.agent.infrastructure.config.RetryConfig;
import com.ai.agent.infrastructure.utils.RetryUtil;
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
 * @Description: Deepseek 平台 LLM 服务实现
 *               支持模型：deepseek-chat / deepseek-reasoner
 *               差异点：
 *               1. deepseek-reasoner 不支持 temperature 参数，自动跳过
 *               2. 响应额外包含 reasoning_content（思维链）和 prompt_cache_hit_tokens
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: DeepseekServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/24
 * @Version: 1.0
 */
@Slf4j
@Service
public class DeepseekServiceImpl implements LlmService {

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG = "[DONE]";
    /** extraParams 中的 skip_temperature 标志，用于 deepseek-reasoner 等不支持 temperature 的模型 */
    private static final String SKIP_TEMPERATURE_KEY = "skip_temperature";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExecutorService streamExecutor;
    private final OkHttpConfig okHttpConfig;
    private final RetryConfig retryConfig;

    public DeepseekServiceImpl(@Qualifier("deepseekStreamExecutor") ExecutorService streamExecutor,
            OkHttpConfig okHttpConfig,
            RetryConfig retryConfig) {
        this.streamExecutor = streamExecutor;
        this.okHttpConfig = okHttpConfig;
        this.retryConfig = retryConfig;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        log.info("[Deepseek-chat] 开始调用, request={}", request);
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();
        LlmResponse result = RetryUtil.retry(() -> {
            Request okRequest = new Request.Builder()
                    .url(request.getEndpoint())
                    .post(RequestBody.create(requestBody, JSON))
                    .headers(Headers.of(buildHeaders(request.getApiKey())))
                    .build();
            try (Response response = okHttpConfig.getLlmClient("deepseek").newCall(okRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    if (response.code() >= 400 && response.code() < 500) {
                        log.error("[Deepseek-chat] HTTP {}，不可重试, body={}", response.code(), responseBody);
                        throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                    }
                    log.warn("[Deepseek-chat] HTTP {} 失败, body={}", response.code(), responseBody);
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                if (responseBody.isEmpty()) {
                    log.error("[Deepseek-chat] 响应体为空");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                return parseResponse(responseBody, request.getModelCode());
            } catch (BizException e) {
                throw e;
            } catch (IOException e) {
                log.error("[Deepseek-chat] IO 异常", e);
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            }
        }, retryConfig.getRetryParam("deepseek"));
        if (result == null) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        log.info("[Deepseek-chat] 调用成功, result={}, costMs={}",
                result, System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        String requestBody = buildRequestBody(request, true);
        log.info("[Deepseek-stream] 开始调用, request={}", request);
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        try {
            streamExecutor.submit(() -> {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                try {
                    Request okRequest = new Request.Builder()
                            .url(request.getEndpoint())
                            .post(RequestBody.create(requestBody, JSON))
                            .headers(Headers.of(buildHeaders(request.getApiKey())))
                            .build();

                    try (Response response = okHttpConfig.getLlmClient("deepseek").newCall(okRequest).execute()) {
                        if (!response.isSuccessful() || response.body() == null) {
                            log.error("[Deepseek-stream] HTTP 失败, code={}", response.code());
                            chunkConsumer.accept("[ERROR]");
                            return;
                        }
                        parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                    }
                } catch (BizException e) {
                    log.error("[Deepseek-stream] 业务异常", e);
                    chunkConsumer.accept("[ERROR]");
                } catch (IOException e) {
                    log.error("[Deepseek-stream] IO 异常", e);
                    chunkConsumer.accept("[ERROR]");
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Deepseek-stream] 线程池已满，拒绝请求", e);
            chunkConsumer.accept("[ERROR]");
        }
    }

    /**
     * Deepseek 当前主力模型（deepseek-chat / deepseek-reasoner）为纯文本推理模型，
     * 不具备图片理解能力，暂不支持多模态，返回 null。
     */
    @Override
    public LlmResponse multimodalChat(LlmRequest request) {
        log.warn("[Deepseek] 暂不支持多模态：当前接入的 deepseek-chat / deepseek-reasoner 为纯文本模型，不具备图片理解能力");
        return null;
    }

    // ==================== 私有方法 ====================

    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModelCode());
        body.put("stream", stream);
        if (stream) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        body.put("messages", buildMessages(request));
        // 读取 extraParams 中的 skip_temperature 标志：true 时跳过 temperature（如 deepseek-reasoner）
        boolean skipTemperature = request.getExtraParams() != null
                && Boolean.TRUE.equals(request.getExtraParams().get(SKIP_TEMPERATURE_KEY));
        if (request.getTemperature() != null && !skipTemperature) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.getExtraParams() != null) {
            request.getExtraParams().forEach((k, v) -> {
                // skip_temperature 是框架内部控制标志，不透传到平台请求体
                if (!SKIP_TEMPERATURE_KEY.equals(k)) {
                    body.put(k, v);
                }
            });
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    private List<Object> buildMessages(LlmRequest request) {
        List<Object> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole());
            if (msg.isTextOnly()) {
                m.put("content", msg.getTextContent());
            } else {
                m.put("content", buildContentArray(msg));
            }
            messages.add(m);
        }
        return messages;
    }

    private List<Map<String, Object>> buildContentArray(LlmMessage msg) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (MessageContent c : msg.getContents()) {
            switch (c.getType()) {
                case TEXT -> {
                    Map<String, Object> part = new HashMap<>();
                    part.put("type", "text");
                    part.put("text", c.getValue());
                    parts.add(part);
                }
                case IMAGE -> {
                    Map<String, Object> imageUrl = new HashMap<>();
                    imageUrl.put("url", c.getValue());
                    imageUrl.put("detail", c.getDetail() != null ? c.getDetail() : "auto");
                    Map<String, Object> part = new HashMap<>();
                    part.put("type", "image_url");
                    part.put("image_url", imageUrl);
                    parts.add(part);
                }
                default -> throw new BizException(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
            }
        }
        return parts;
    }

    private Map<String, String> buildHeaders(String apiKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private LlmResponse parseResponse(String responseJson, String modelCode) {
        try {
            JsonNode root  = MAPPER.readTree(responseJson);
            JsonNode usage = root.path("usage");
            int input  = usage.path("prompt_tokens").asInt(0);
            int output = usage.path("completion_tokens").asInt(0);
            // Deepseek 私有：prompt_cache_hit_tokens 在顶层 usage（非 details 嵌套）
            int cacheHit = usage.path("prompt_cache_hit_tokens").asInt(0);

            List<LlmChoice> choices = new ArrayList<>();
            for (JsonNode c : root.path("choices")) {
                JsonNode msg = c.path("message");
                String reasoningContent = null;
                JsonNode reasoningNode = msg.path("reasoning_content");
                if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
                    reasoningContent = reasoningNode.asText("");
                }
                choices.add(LlmChoice.builder()
                        .content(msg.path("content").asText(""))
                        .reasoningContent(reasoningContent)
                        .finishReason(c.path("finish_reason").asText(""))
                        .build());
            }
            return LlmResponse.builder()
                    .requestId(root.path("id").asText(null))
                    .modelCode(modelCode)
                    .createdAt(root.path("created").asLong(0) > 0 ? root.path("created").asLong() : null)
                    .choices(choices)
                    .usage(LlmUsage.builder()
                            .inputTokens(input)
                            .outputTokens(output)
                            .totalTokens(!usage.path("total_tokens").isMissingNode() ? usage.path("total_tokens").asInt() : input + output)
                            // Deepseek cacheHit 在顶层 usage（非 details 嵌套），有字段就填包括 0
                            .inputTokensDetails(!usage.path("prompt_cache_hit_tokens").isMissingNode()
                                    ? LlmInputTokensDetails.builder()
                                            .cachedTokens(cacheHit)
                                            .build()
                                    : null)
                            .outputTokensDetails(!usage.path("completion_tokens_details").isMissingNode()
                                    ? LlmOutputTokensDetails.builder()
                                            .reasoningTokens(usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0))
                                            .build()
                                    : null)
                            .build())
                    .build();
        } catch (IOException e) {
            log.error("[Deepseek-chat] 响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    private void parseStreamResponse(ResponseBody responseBody, String modelCode, Consumer<String> chunkConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SSE_DATA_PREFIX)) continue;
                String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (SSE_DONE_FLAG.equals(data)) {
                    chunkConsumer.accept(null);
                    return;
                }
                JsonNode delta = MAPPER.readTree(data).path("choices").path(0).path("delta");
                String reasoningChunk = delta.path("reasoning_content").asText("");
                if (!reasoningChunk.isEmpty()) {
                    chunkConsumer.accept(reasoningChunk);
                }
                String chunk = delta.path("content").asText("");
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }
            }
        } catch (IOException e) {
            log.error("[Deepseek-stream] 流式响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }
}

