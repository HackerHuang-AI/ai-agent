package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * @Description: 豆包（火山方舟）平台 LLM 服务实现
 *               支持 Chat Completions 协议（文本对话）和 Responses API（多模态对话）
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: DoubaoServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/24
 * @Version: 1.0
 */
@Slf4j
@Service
public class DoubaoServiceImpl implements LlmService {

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG = "[DONE]";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public LlmResponse chat(LlmRequest request) {
        String requestBody = buildRequestBody(request, false);
        log.info("[Doubao-chat] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        long start = System.currentTimeMillis();

        try {
            Request okRequest = new Request.Builder()
                    .url(request.getEndpoint())
                    .post(RequestBody.create(requestBody, JSON))
                    .headers(Headers.of(buildHeaders(request.getApiKey())))
                    .build();

            try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("[Doubao-chat] HTTP 失败, model={}, code={}", request.getModelCode(), response.code());
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                LlmResponse result = parseResponse(response.body().string(), request.getModelCode());
                log.info("[Doubao-chat] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                        request.getModelCode(), result.getInputTokens(), result.getOutputTokens(),
                        System.currentTimeMillis() - start);
                return result;
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao-chat] IO 异常, model={}", request.getModelCode(), e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        String requestBody = buildRequestBody(request, true);
        log.info("[Doubao-stream] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());

        try {
            Request okRequest = new Request.Builder()
                    .url(request.getEndpoint())
                    .post(RequestBody.create(requestBody, JSON))
                    .headers(Headers.of(buildHeaders(request.getApiKey())))
                    .build();

            try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("[Doubao-stream] HTTP 失败, model={}, code={}", request.getModelCode(), response.code());
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao-stream] IO 异常, model={}", request.getModelCode(), e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    /**
     * 多模态对话（Responses API）
     * 支持图片+文本混合输入，调用 /v3/responses 协议
     */
    public LlmResponse multimodalChat(String model, List<Map<String, Object>> input, String apiKey, String endpoint) {
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("model", model);
        bodyMap.put("input", input);

        log.info("[Doubao-multimodal] 开始调用, model={}, endpoint={}", model, endpoint);
        long start = System.currentTimeMillis();

        try {
            String requestBody = MAPPER.writeValueAsString(bodyMap);
            Request okRequest = new Request.Builder()
                    .url(endpoint)
                    .post(RequestBody.create(requestBody, JSON))
                    .headers(Headers.of(buildHeaders(apiKey)))
                    .build();

            try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("[Doubao-multimodal] HTTP 失败, model={}, code={}", model, response.code());
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                LlmResponse result = parseMultimodalResponse(response.body().string(), model);
                log.info("[Doubao-multimodal] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                        model, result.getInputTokens(), result.getOutputTokens(),
                        System.currentTimeMillis() - start);
                return result;
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao-multimodal] IO 异常, model={}", model, e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
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
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
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
            JsonNode root = MAPPER.readTree(responseJson);
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
            String finishReason = choice.path("finish_reason").asText("");
            JsonNode usage = root.path("usage");
            return LlmResponse.builder()
                    .content(content)
                    .modelCode(modelCode)
                    .inputTokens(usage.path("prompt_tokens").asInt(0))
                    .outputTokens(usage.path("completion_tokens").asInt(0))
                    .finishReason(finishReason)
                    .build();
        } catch (IOException e) {
            log.error("[Doubao-chat] 响应解析失败, model={}", modelCode, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    private LlmResponse parseMultimodalResponse(String responseJson, String model) {
        try {
            JsonNode root = MAPPER.readTree(responseJson);
            String content = root.path("output").path(0).path("content").path(0).path("text").asText("");
            JsonNode usage = root.path("usage");
            return LlmResponse.builder()
                    .content(content)
                    .modelCode(model)
                    .inputTokens(usage.path("input_tokens").asInt(0))
                    .outputTokens(usage.path("output_tokens").asInt(0))
                    .finishReason("stop")
                    .build();
        } catch (IOException e) {
            log.error("[Doubao-multimodal] 响应解析失败, model={}", model, e);
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
                String chunk = delta.path("content").asText("");
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }
            }
        } catch (IOException e) {
            log.error("[Doubao-stream] 流式响应解析失败, model={}", modelCode, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }
}

