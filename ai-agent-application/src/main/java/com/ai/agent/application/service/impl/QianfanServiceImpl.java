package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.QianfanBO;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.enums.http.QianfanHttpCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.utils.AppRetryUtil;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.ai.agent.infrastructure.config.RetryConfig;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
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
 * @Description: Moonshot（Kimi）平台 LLM 服务实现
 *               OpenAI 高度兼容协议，与标准实现无差异。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: QianfanServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Slf4j
@Service
public class QianfanServiceImpl implements LlmService {

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG   = "[DONE]";
    private static final MediaType    JSON        = MediaType.parse("application/json; charset=utf-8");
    
    private static final ObjectMapper MAPPER      = new ObjectMapper();


    private final ExecutorService streamExecutor;
    private final OkHttpConfig okHttpConfig;
    private final RetryConfig retryConfig;

    public QianfanServiceImpl(@Qualifier("qianfanStreamExecutor") ExecutorService streamExecutor,
            OkHttpConfig okHttpConfig,
            RetryConfig retryConfig) {
        this.streamExecutor = streamExecutor;
        this.okHttpConfig = okHttpConfig;
        this.retryConfig = retryConfig;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        fillDefaults(request);
        log.info("[Qianfan-chat] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();

        LlmResponse result = AppRetryUtil.retry(() -> {
            Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
            try (Response response = okHttpConfig.getLlmClient("qianfan").newCall(okRequest).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        String platformErr = extractErrorMessage(responseBody);
                        log.error("[Qianfan-chat] HTTP {} 失败, platformError={}", response.code(), platformErr);
                        throwByHttpCode(response.code(), platformErr);
                    }
                    if (responseBody.isEmpty()) {
                        log.error("[Qianfan-chat] 响应体为空");
                        throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                    }
                    return parseResponse(responseBody, request.getModelCode());
            }
        }, retryConfig.getRetryParam("qianfan"));
        if (result == null) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        log.info("[Qianfan-chat] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                                request.getModelCode(), result.getUsage().getInputTokens(), result.getUsage().getOutputTokens(),
                                System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        fillDefaults(request);
        String requestBody = buildRequestBody(request, true);
        log.info("[Qianfan-stream] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        try {
            streamExecutor.submit(() -> {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                try {
                    Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
                    Response response = AppRetryUtil.retryForStream(() -> {
                        Response resp = okHttpConfig.getLlmClient("qianfan").newCall(okRequest).execute();
                        if (!resp.isSuccessful()) {
                            String errBody = resp.body() != null ? resp.body().string() : "";
                            String platformMsg = extractErrorMessage(errBody);
                            log.error("[Qianfan-stream] HTTP {} 失败, platformError={}", resp.code(), platformMsg);
                            resp.close();
                            throwByHttpCode(resp.code(), platformMsg);
                        }
                        return resp;
                    }, retryConfig.getRetryParam("qianfan"));
                    if (response == null || response.body() == null) {
                        log.error("[Qianfan] 连接失败或响应体为空");
                        chunkConsumer.accept("[ERROR]");
                        return;
                    }
                    try {
                        parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                    } finally {
                        response.close();
                    }
                } catch (BizException e) {
                    log.error("[Qianfan-stream] 业务异常", e);
                    chunkConsumer.accept("[ERROR]");
                } catch (IOException e) {
                    log.error("[Qianfan-stream] IO 异常", e);
                    chunkConsumer.accept("[ERROR]");
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Qianfan-stream] 线程池已满，拒绝请求", e);
            chunkConsumer.accept("[ERROR]");
        }
    }

    /**
     * 百度千帆当前接入版本为纯文本模型，不具备图片理解能力，暂不支持多模态，返回 null。
     */
    @Override
    public LlmResponse multimodalChat(LlmRequest request) {
        log.warn("[Qianfan] 暂不支持多模态：当前接入的百度千帆版本为纯文本模型，不具备图片理解能力");
        return null;
    }

    @Override
    public List<LlmModelInfo> listModels(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            QianfanBO cfg = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_QIANFAN, "chat", QianfanBO.class);
            apiKey = cfg != null ? cfg.getApiKey() : null;
        }
        if (StringUtils.isBlank(apiKey)) {
            log.error("[Qianfan-models] apiKey 未配置");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        Request okRequest = new Request.Builder()
                .url("https://qianfan.baidubce.com/v2/models")
                .get()
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();
        try (Response response = okHttpConfig.getLlmClient("qianfan").newCall(okRequest).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("[Qianfan-models] HTTP {} 失败, body={}", response.code(), truncate(body));
                throwByHttpCode(response.code(), extractErrorMessage(body));
            }
            JsonNode root = MAPPER.readTree(body);
            List<LlmModelInfo> result = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                result.add(parseQianfanModel(item));
            }
            log.info("[Qianfan-models] 获取模型列表成功, count={}", result.size());
            return result;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Qianfan-models] IO 异常", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    private LlmModelInfo parseQianfanModel(JsonNode item) {
        JsonNode arch = item.path("architecture");
        List<String> inputModalities  = jsonArrayToList(arch.path("input_modalities"));
        List<String> outputModalities = jsonArrayToList(arch.path("output_modalities"));

        Integer contextWindow    = item.path("context_length").isNull() || item.path("context_length").isMissingNode()
                ? null : item.path("context_length").asInt(0);
        Integer maxOutputTokens  = item.path("max_completions_tokens").isNull() || item.path("max_completions_tokens").isMissingNode()
                ? null : item.path("max_completions_tokens").asInt(0);
        Integer maxInputTokens   = item.path("prompt_tokens").isNull() || item.path("prompt_tokens").isMissingNode()
                ? null : item.path("prompt_tokens").asInt(0);

        long created = item.path("created").asLong(0);
        return LlmModelInfo.builder()
                .id(item.path("id").asText(null))
                .name(item.path("id").asText(null))
                .ownedBy(item.path("owned_by").asText(null))
                .created(created > 0 ? created : null)
                .status("active")
                .domain(item.path("type").asText(null))
                .inputModalities(inputModalities.isEmpty() ? null : inputModalities)
                .outputModalities(outputModalities.isEmpty() ? null : outputModalities)
                .contextWindow(contextWindow)
                .maxInputTokens(maxInputTokens)
                .maxOutputTokens(maxOutputTokens)
                .build();
    }

    private List<String> jsonArrayToList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    // ==================== 凭证兜底 ====================

    private void fillDefaults(LlmRequest request) {
        QianfanBO cfg = null;
        if (StringUtils.isBlank(request.getApiKey())
                || StringUtils.isBlank(request.getEndpoint())
                || StringUtils.isBlank(request.getModelCode())) {
            cfg = NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_QIANFAN, "chat", QianfanBO.class);
        }
        if (StringUtils.isBlank(request.getApiKey()))
            request.setApiKey(cfg != null ? cfg.getApiKey() : null);
        if (StringUtils.isBlank(request.getEndpoint()))
            request.setEndpoint(cfg != null ? cfg.getEndpoint() : null);
        if (StringUtils.isBlank(request.getModelCode()))
            request.setModelCode(cfg != null ? cfg.getModelCode() : null);
        if (StringUtils.isBlank(request.getApiKey())) {
            log.error("[千帆] apiKey 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        if (StringUtils.isBlank(request.getEndpoint())) {
            log.error("[千帆] endpoint 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        if (StringUtils.isBlank(request.getModelCode())) {
            log.error("[千帆] modelCode 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    // ==================== 请求构建 ====================

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
        if (request.getTopP() != null) {
            body.put("top_p", request.getTopP());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }
        if (request.getTopK() != null) {
            body.put("top_k", request.getTopK());
        }
        if (request.getFrequencyPenalty() != null) {
            body.put("frequency_penalty", request.getFrequencyPenalty());
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

    private Request buildOkRequest(String endpoint, String apiKey, String requestBody) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
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
            JsonNode root  = MAPPER.readTree(responseJson);
            JsonNode usage = root.path("usage");
            int input  = usage.path("prompt_tokens").asInt(0);
            int output = usage.path("completion_tokens").asInt(0);

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
                            .inputTokensDetails(!usage.path("prompt_tokens_details").isMissingNode()
                                    ? LlmInputTokensDetails.builder()
                                            .cachedTokens(usage.path("prompt_tokens_details").path("cached_tokens").asInt(0))
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
            log.error("[Qianfan-chat] 响应解析失败", e);
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
            // 流正常读完但未收到 [DONE] 帧（服务端偶发），兜底关闭 SSE 连接
            chunkConsumer.accept(null);
        } catch (IOException e) {
            log.error("[Qianfan-stream] 流式响应解析失败, model={}", modelCode, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    // ==================== 工具方法 ====================

    private void throwByHttpCode(int httpCode, String platformMsg) {
        ErrorCodeEnum errorCode;
        if (httpCode == QianfanHttpCodeEnum.UNAUTHORIZED.getCode()) {
            errorCode = ErrorCodeEnum.LLM_AUTH_FAILED;
        } else if (httpCode == QianfanHttpCodeEnum.BAD_REQUEST.getCode()
                || httpCode == QianfanHttpCodeEnum.UNPROCESSABLE.getCode()) {
            errorCode = ErrorCodeEnum.PARAM_ILLEGAL;
        } else if (httpCode == QianfanHttpCodeEnum.RATE_LIMIT.getCode()) {
            errorCode = ErrorCodeEnum.LLM_RATE_LIMIT;
        } else {
            errorCode = ErrorCodeEnum.LLM_CALL_FAILED;
        }
        throw new BizException(errorCode, platformMsg);
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode errorNode = root.path("error");
            String msg = errorNode.path("message").asText("");
            if (msg.isEmpty()) return truncate(responseBody);
            String code = errorNode.path("code").asText("");
            return code.isEmpty() ? msg : "[" + code + "] " + msg;
        } catch (Exception e) {
            return truncate(responseBody);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

}

