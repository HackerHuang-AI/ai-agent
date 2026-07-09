package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
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
 * @Description: 智谱 GLM 平台 LLM 服务实现
 *               OpenAI 兼容协议，唯一差异：认证方式为本地自签 JWT（非直接 Bearer API Key）。
 *               JWT 规则：用 API Key（格式 id.secret）中的 secret 做 HMAC-SHA256 签名，有效期 30 分钟。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: ZhipuServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Slf4j
@Service
public class ZhipuServiceImpl implements LlmService {

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG   = "[DONE]";
    private static final MediaType    JSON        = MediaType.parse("application/json; charset=utf-8");
    
    private static final ObjectMapper MAPPER      = new ObjectMapper();

    /** JWT 有效期：30 分钟 */
    private static final long JWT_EXPIRE_MS  = 30 * 60 * 1000L;

    private final ExecutorService streamExecutor;
    private final OkHttpConfig okHttpConfig;

    public ZhipuServiceImpl(@Qualifier("zhipuStreamExecutor") ExecutorService streamExecutor,
            OkHttpConfig okHttpConfig) {
        this.streamExecutor = streamExecutor;
        this.okHttpConfig = okHttpConfig;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        log.info("[Zhipu-chat] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();

        try {
            Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
            try (Response response = okHttpConfig.getLlmClient().newCall(okRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String platformErr = extractErrorMessage(responseBody);
                    if (response.code() >= 400 && response.code() < 500) {
                        log.error("[Zhipu-chat] HTTP {}，不可重试, platformError={}", response.code(), platformErr);
                        throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED, platformErr);
                    }
                    log.warn("[Zhipu-chat] HTTP {} 失败, platformError={}", response.code(), platformErr);
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED, platformErr);
                }
                if (responseBody.isEmpty()) {
                    log.error("[Zhipu-chat] 响应体为空");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                LlmResponse result = parseResponse(responseBody, request.getModelCode());
                log.info("[Zhipu-chat] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                                request.getModelCode(), result.getInputTokens(), result.getOutputTokens(),
                                System.currentTimeMillis() - start);
                return result;
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Zhipu-chat] IO 异常", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        String requestBody = buildRequestBody(request, true);
        log.info("[Zhipu-stream] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        try {
            streamExecutor.submit(() -> {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                try {
                    Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
                    try (Response response = okHttpConfig.getLlmClient().newCall(okRequest).execute()) {
                        if (!response.isSuccessful() || response.body() == null) {
                            String errBody = response.body() != null ? response.body().string() : "";
                            log.error("[Zhipu-stream] HTTP 失败, code={}, platformError={}", response.code(), extractErrorMessage(errBody));
                            chunkConsumer.accept("[ERROR]");
                            return;
                        }
                        parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                    }
                } catch (BizException e) {
                    log.error("[Zhipu-stream] 业务异常", e);
                    chunkConsumer.accept("[ERROR]");
                } catch (IOException e) {
                    log.error("[Zhipu-stream] IO 异常", e);
                    chunkConsumer.accept("[ERROR]");
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Zhipu-stream] 线程池已满，拒绝请求", e);
            chunkConsumer.accept("[ERROR]");
        }
    }

    // ==================== 请求构建 ====================

    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModelCode());
        body.put("stream", stream);
        body.put("messages", buildMessages(request));
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.getExtraParams() != null) {
            body.putAll(request.getExtraParams());
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

    /**
     * 智谱特有：用 API Key（id.secret 格式）生成 JWT Token，用于 Bearer 认证。
     * 每次调用都生成新 Token（有效期 30 分钟），避免 Token 过期问题。
     */
    private String generateJwtToken(String apiKey) {
        String[] parts = apiKey.split("\\.");
        if (parts.length != 2) {
            log.error("[Zhipu] API Key 格式错误，应为 id.secret 格式");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        String id = parts[0];
        String secret = parts[1];
        long now = System.currentTimeMillis();

        Algorithm algorithm = Algorithm.HMAC256(secret);
        return JWT.create()
                .withKeyId(id)
                .withPayload(Map.of(
                        "api_key",   id,
                        "timestamp", now,
                        "exp",       now + JWT_EXPIRE_MS
                ))
                .withHeader(Map.of("alg", "HS256", "sign_type", "SIGN"))
                .sign(algorithm);
    }

    private Request buildOkRequest(String endpoint, String apiKey, String requestBody) {
        String jwtToken = generateJwtToken(apiKey);
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + jwtToken);
        headers.put("Content-Type", "application/json");
        return new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(requestBody, JSON))
                .headers(Headers.of(headers))
                .build();
    }

    // ==================== 响应解析 ====================

    private LlmResponse parseResponse(String responseJson, String modelCode) {
        try {
            JsonNode root   = MAPPER.readTree(responseJson);
            JsonNode choice = root.path("choices").path(0);
            String content  = choice.path("message").path("content").asText("");
            String finish   = choice.path("finish_reason").asText("");
            JsonNode usage  = root.path("usage");
            return LlmResponse.builder()
                    .content(content)
                    .modelCode(modelCode)
                    .inputTokens(usage.path("prompt_tokens").asInt(0))
                    .outputTokens(usage.path("completion_tokens").asInt(0))
                    .finishReason(finish)
                    .build();
        } catch (IOException e) {
            log.error("[Zhipu-chat] 响应解析失败", e);
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
                String chunk = MAPPER.readTree(data).path("choices").path(0).path("delta").path("content").asText("");
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }
            }
        } catch (IOException e) {
            log.error("[Zhipu-stream] 流式响应解析失败, model={}", modelCode, e);
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

