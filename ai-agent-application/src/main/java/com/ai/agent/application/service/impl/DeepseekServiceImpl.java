package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.DeepseekBO;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.enums.http.DeepseekHttpCode;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.ai.agent.infrastructure.config.RetryConfig;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.ai.agent.application.common.AppRetryUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.lang3.StringUtils;
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
    /** extraParams 内部控制标志：跳过 temperature（deepseek-reasoner 不支持 temperature） */
    private static final String SKIP_TEMPERATURE_KEY = "skip_temperature";
    /** extraParams 专属 key：思考模式类型，值为 "enabled" 或 "disabled"，组装为 thinking.type */
    private static final String THINKING_TYPE_KEY = "thinking_type";
    /** extraParams 专属 key：推理强度，值为 "high" 或 "max"，组装为 thinking.reasoning_effort */
    private static final String REASONING_EFFORT_KEY = "reasoning_effort";
    /** extraParams 透传时需要过滤的框架内部 key */
    private static final Set<String> INTERNAL_KEYS = Set.of(
            SKIP_TEMPERATURE_KEY, THINKING_TYPE_KEY, REASONING_EFFORT_KEY);
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
        fillDefaults(request);
        log.info("[Deepseek-chat] 开始调用, request={}", request);
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();
        LlmResponse result = AppRetryUtil.retry(() -> {
            Request okRequest = new Request.Builder()
                    .url(request.getEndpoint())
                    .post(RequestBody.create(requestBody, JSON))
                    .headers(Headers.of(buildHeaders(request.getApiKey())))
                    .build();
            try (Response response = okHttpConfig.getLlmClient("deepseek").newCall(okRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("[Deepseek-chat] HTTP {} 失败, body={}", response.code(), responseBody);
                    throwByHttpCode(response.code(), responseBody);
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
        fillDefaults(request);
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
                        if (!response.isSuccessful()) {
                            String errorBody = response.body() != null ? response.body().string() : "";
                            log.error("[Deepseek-stream] HTTP {} 失败, body={}", response.code(), errorBody);
                            throwByHttpCode(response.code(), errorBody);
                        }
                        if (response.body() == null) {
                            log.error("[Deepseek-stream] 响应体为空");
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

    // ==================== 凭证兜底 ====================

    /**
     * 入参兜底：调用方未传的字段从 Nacos ai-agent-deepseek.json 的 chat 块补全。
     * 补完后校验必填项，缺失时抛异常。
     */
    private void fillDefaults(LlmRequest request) {
        DeepseekBO cfg = null;
        if (StringUtils.isBlank(request.getApiKey())
                || StringUtils.isBlank(request.getEndpoint())
                || StringUtils.isBlank(request.getModelCode())) {
            cfg = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_DEEPSEEK, "chat", DeepseekBO.class);
        }
        if (StringUtils.isBlank(request.getApiKey()))
            request.setApiKey(cfg != null ? cfg.getApiKey() : null);
        if (StringUtils.isBlank(request.getEndpoint()))
            request.setEndpoint(cfg != null ? cfg.getEndpoint() : null);
        if (StringUtils.isBlank(request.getModelCode()))
            request.setModelCode(cfg != null ? cfg.getModelCode() : null);
        if (StringUtils.isBlank(request.getApiKey())) {
            log.error("[Deepseek] apiKey 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        if (StringUtils.isBlank(request.getEndpoint())) {
            log.error("[Deepseek] endpoint 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        if (StringUtils.isBlank(request.getModelCode())) {
            log.error("[Deepseek] modelCode 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    // ==================== 私有方法 ====================

    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> extra = request.getExtraParams();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModelCode());
        body.put("stream", stream);
        if (stream) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        body.put("messages", buildMessages(request));

        // skip_temperature：deepseek-reasoner 不支持 temperature
        boolean skipTemperature = extra != null && Boolean.TRUE.equals(extra.get(SKIP_TEMPERATURE_KEY));
        if (request.getTemperature() != null && !skipTemperature) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        // Deepseek frequency_penalty 已标记为 deprecated（官方文档：传入该参数不会产生任何效果），不再注入

        // thinking：从 extraParams 中提取 thinking_type / reasoning_effort，组装为平台所需对象结构
        // 示例：extraParams.thinking_type="enabled", extraParams.reasoning_effort="high"
        //       → 请求体：{"thinking": {"type": "enabled", "reasoning_effort": "high"}}
        if (extra != null && extra.containsKey(THINKING_TYPE_KEY)) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", extra.get(THINKING_TYPE_KEY));
            if (extra.containsKey(REASONING_EFFORT_KEY)) {
                thinking.put("reasoning_effort", extra.get(REASONING_EFFORT_KEY));
            }
            body.put("thinking", thinking);
        }

        // 透传其余 extraParams（过滤框架内部 key）
        // 支持的透传参数包括：response_format、tools、tool_choice、stop、logprobs、top_logprobs、user_id 等
        if (extra != null) {
            extra.forEach((k, v) -> {
                if (!INTERNAL_KEYS.contains(k)) {
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

    /**
     * 按 Deepseek HTTP 错误码路由到对应业务异常，并携带平台返回的 error.message。
     * 401 → 认证失败；402 → 余额不足；422/400 → 参数错误；429 → 限速；其余 → 通用调用失败。
     */
    private void throwByHttpCode(int httpCode, String responseBody) {
        String platformMsg = extractPlatformMessage(responseBody);
        ErrorCodeEnum errorCode;
        if (httpCode == DeepseekHttpCode.UNAUTHORIZED.getCode()) {
            errorCode = ErrorCodeEnum.LLM_AUTH_FAILED;
        } else if (httpCode == DeepseekHttpCode.INSUFFICIENT_FUNDS.getCode()) {
            errorCode = ErrorCodeEnum.LLM_INSUFFICIENT_BALANCE;
        } else if (httpCode == DeepseekHttpCode.BAD_REQUEST.getCode()
                || httpCode == DeepseekHttpCode.UNPROCESSABLE.getCode()) {
            errorCode = ErrorCodeEnum.PARAM_ILLEGAL;
        } else if (httpCode == DeepseekHttpCode.RATE_LIMIT.getCode()) {
            errorCode = ErrorCodeEnum.LLM_RATE_LIMIT;
        } else {
            errorCode = ErrorCodeEnum.LLM_CALL_FAILED;
        }
        throw new BizException(errorCode, platformMsg);
    }

    /**
     * 从 Deepseek 错误响应体中提取 error.message 字段。
     * 格式：{"error": {"message": "...", "type": "...", "code": "..."}}
     * 解析失败时返回原始响应体（截断至 200 字符）。
     */
    private String extractPlatformMessage(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) return "平台未返回错误信息";
        try {
            JsonNode error = MAPPER.readTree(responseBody).path("error");
            if (!error.isMissingNode()) {
                String msg = error.path("message").asText("");
                String type = error.path("code").asText("");
                return msg.isEmpty() ? responseBody : (type.isEmpty() ? msg : "[" + type + "] " + msg);
            }
        } catch (IOException ignored) {
        }
        return responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody;
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
            // 流正常读完但未收到 [DONE] 帧（服务端偶发），兜底关闭 SSE 连接
            chunkConsumer.accept(null);
        } catch (IOException e) {
            log.error("[Deepseek-stream] 流式响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }
}

