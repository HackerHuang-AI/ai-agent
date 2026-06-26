package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.infrastructure.utils.OkHttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final String REASONER_MODEL = "deepseek-reasoner";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = OkHttpUtil.getLlmClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExecutorService STREAM_EXECUTOR = Executors.newCachedThreadPool();

    @Override
    public LlmResponse chat(LlmRequest request) {
        log.info("[Deepseek-chat] 开始调用, request={}", request);
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();
        Request okRequest = new Request.Builder()
                .url(request.getEndpoint())
                .post(RequestBody.create(requestBody, JSON))
                .headers(Headers.of(buildHeaders(request.getApiKey())))
                .build();

        try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful() || responseBody.isEmpty()) {
                log.error("[Deepseek-chat] HTTP 失败, code={}", response.code());
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            }
            LlmResponse result = parseResponse(responseBody, request.getModelCode());
            log.info("[Deepseek-chat] 调用成功, result={}, costMs={}",
                    result, System.currentTimeMillis() - start);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Deepseek-chat] IO 异常", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        String requestBody = buildRequestBody(request, true);
        log.info("[Deepseek-stream] 开始调用, request={}", request);
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        STREAM_EXECUTOR.submit(() -> {
            if (mdcContext != null) MDC.setContextMap(mdcContext);
            try {
                Request okRequest = new Request.Builder()
                        .url(request.getEndpoint())
                        .post(RequestBody.create(requestBody, JSON))
                        .headers(Headers.of(buildHeaders(request.getApiKey())))
                        .build();

                try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        log.error("[Deepseek-stream] HTTP 失败, code={}", response.code());
                        chunkConsumer.accept(null);
                        return;
                    }
                    parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                }
            } catch (BizException e) {
                log.error("[Deepseek-stream] 业务异常", e);
                chunkConsumer.accept(null);
            } catch (IOException e) {
                log.error("[Deepseek-stream] IO 异常", e);
                chunkConsumer.accept(null);
            } finally {
                MDC.clear();
            }
        });
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
        // reasoner 模型不支持 temperature，自动跳过
        if (request.getTemperature() != null && !REASONER_MODEL.equals(request.getModelCode())) {
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
            JsonNode message = choice.path("message");
            String content = message.path("content").asText("");
            String finishReason = choice.path("finish_reason").asText("");
            JsonNode usage = root.path("usage");

            LlmResponse.LlmResponseBuilder builder = LlmResponse.builder()
                    .content(content)
                    .modelCode(modelCode)
                    .inputTokens(usage.path("prompt_tokens").asInt(0))
                    .outputTokens(usage.path("completion_tokens").asInt(0))
                    .finishReason(finishReason);

            JsonNode reasoningNode = message.path("reasoning_content");
            if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
                builder.reasoningContent(reasoningNode.asText(""));
            }
            builder.cacheHitTokens(usage.path("prompt_cache_hit_tokens").asInt(0));

            return builder.build();
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

