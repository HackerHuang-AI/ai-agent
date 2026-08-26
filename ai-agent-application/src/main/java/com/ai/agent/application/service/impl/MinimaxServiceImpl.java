package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.MinimaxBO;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.enums.http.MinimaxHttpCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.utils.AppRetryUtil;
import com.ai.agent.infrastructure.config.NacosConfig;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.ai.agent.infrastructure.config.RetryConfig;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.enums.OkHttpConfigEnum;
import com.ai.agent.infrastructure.enums.RetryConfigEnum;
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
 * @Description: Minimax 平台 LLM 服务实现。
 *               协议基本兼容 OpenAI，关键差异：
 *               1. messages 每条消息需携带 name 字段（system→"assistant"，user→"user"，assistant→"MM智能助手"）
 *               2. HTTP 200 不等于业务成功，需检查响应体中的 base_resp.status_code == 0
 *               平台私有参数（mask_sensitive_info 等）通过 extraParams 传入。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: MinimaxServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/29
 * @Version: 1.0
 */
@Slf4j
@Service
public class MinimaxServiceImpl implements LlmService {

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG   = "[DONE]";
    private static final MediaType    JSON        = MediaType.parse("application/json; charset=utf-8");
    
    private static final ObjectMapper MAPPER      = new ObjectMapper();


    private final ExecutorService streamExecutor;
    private final OkHttpConfig okHttpConfig;
    private final RetryConfig retryConfig;
    private final NacosConfig nacosConfig;

    public MinimaxServiceImpl(@Qualifier("minimaxStreamExecutor") ExecutorService streamExecutor,
            OkHttpConfig okHttpConfig,
            RetryConfig retryConfig,
            NacosConfig nacosConfig) {
        this.streamExecutor = streamExecutor;
        this.okHttpConfig = okHttpConfig;
        this.retryConfig = retryConfig;
        this.nacosConfig = nacosConfig;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        fillDefaults(request);
        log.info("[Minimax-chat] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();

        LlmResponse result = AppRetryUtil.retry(() -> {
            Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
            try (Response response = okHttpConfig.getClientByPlatform(OkHttpConfigEnum.MINIMAX).newCall(okRequest).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        String platformErr = extractErrorMessage(responseBody);
                        log.error("[Minimax-chat] HTTP {} 失败, platformError={}", response.code(), platformErr);
                        throwByHttpCode(response.code(), platformErr);
                    }
                    if (responseBody.isEmpty()) {
                        log.error("[Minimax-chat] 响应体为空");
                        throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                    }
                    return parseResponse(responseBody, request.getModelCode());
            }
        }, retryConfig.getRetryParam(RetryConfigEnum.MINIMAX));
        if (result == null) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        log.info("[Minimax-chat] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                                request.getModelCode(), result.getUsage().getInputTokens(), result.getUsage().getOutputTokens(),
                                System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        fillDefaults(request);
        String requestBody = buildRequestBody(request, true);
        log.info("[Minimax-stream] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        try {
            streamExecutor.submit(() -> {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                try {
                    Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
                    Response response = AppRetryUtil.retryForStream(() -> {
                        Response resp = okHttpConfig.getClientByPlatform(OkHttpConfigEnum.MINIMAX).newCall(okRequest).execute();
                        if (!resp.isSuccessful()) {
                            String errBody = resp.body() != null ? resp.body().string() : "";
                            String platformMsg = extractErrorMessage(errBody);
                            log.error("[Minimax-stream] HTTP {} 失败, platformError={}", resp.code(), platformMsg);
                            resp.close();
                            throwByHttpCode(resp.code(), platformMsg);
                        }
                        return resp;
                    }, retryConfig.getRetryParam(RetryConfigEnum.MINIMAX));
                    if (response == null || response.body() == null) {
                        log.error("[Minimax] 连接失败或响应体为空");
                        chunkConsumer.accept("[ERROR]");
                        return;
                    }
                    try {
                        parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                    } finally {
                        response.close();
                    }
                } catch (BizException e) {
                    log.error("[Minimax-stream] 业务异常", e);
                    chunkConsumer.accept("[ERROR]");
                } catch (Exception e) {
                    log.error("[Minimax-stream] 未预期异常", e);
                    chunkConsumer.accept("[ERROR]");
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Minimax-stream] 线程池已满，拒绝请求", e);
            chunkConsumer.accept("[ERROR]");
        }
    }

    /**
     * MiniMax 官方平台已提供视觉模型；当前独立多模态入口尚未适配，返回 null。
     */
    @Override
    public LlmResponse multimodalChat(LlmRequest request) {
        log.warn("[Minimax] 多模态接口暂未适配：MiniMax 已提供视觉模型，当前独立多模态入口尚未完成适配");
        return null;
    }

    @Override
    public LlmModelPage listModels(String apiKey, int pageNo, int pageSize) {
        return LlmModelPage.of(fetchModels(apiKey), pageNo, pageSize);
    }

    private List<LlmModelInfo> fetchModels(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            MinimaxBO cfg = nacosConfig.getObject(NacosDataIdEnum.AI_AGENT_MINIMAX, "chat", MinimaxBO.class);
            apiKey = cfg != null ? cfg.getApiKey() : null;
        }
        if (StringUtils.isBlank(apiKey)) throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        Request request = new Request.Builder().url("https://api.minimaxi.com/v1/models").get()
                .header("Authorization", "Bearer " + apiKey).build();
        try (Response response = okHttpConfig.getClientByPlatform(OkHttpConfigEnum.MINIMAX).newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) throwByHttpCode(response.code(), extractErrorMessage(body));
            List<LlmModelInfo> models = new ArrayList<>();
            for (JsonNode item : MAPPER.readTree(body).path("data")) {
                models.add(LlmModelInfo.builder()
                        .id(item.path("id").asText(null))
                        .name(item.path("id").asText(null))
                        .ownedBy(item.path("owned_by").asText(null))
                        .created(item.path("created").asLong(0))
                        .build());
            }
            log.info("[Minimax-models] 获取模型列表成功, count={}", models.size());
            return models;
        } catch (IOException e) {
            log.error("[Minimax-models] 获取模型列表失败", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    // ==================== 凭证兜底 ====================

    /**
     * 入参兜底：调用方未传的字段从 Nacos ai-agent-minimax.json 的 chat 块补全。
     * 补完后校验必填项，缺失时抛异常。
     */
    private void fillDefaults(LlmRequest request) {
        MinimaxBO cfg = null;
        if (StringUtils.isBlank(request.getApiKey())
                || StringUtils.isBlank(request.getEndpoint())
                || StringUtils.isBlank(request.getModelCode())) {
            cfg = nacosConfig.getObject(NacosDataIdEnum.AI_AGENT_MINIMAX, "chat", MinimaxBO.class);
        }
        if (StringUtils.isBlank(request.getApiKey()))
            request.setApiKey(cfg != null ? cfg.getApiKey() : null);
        if (StringUtils.isBlank(request.getEndpoint()))
            request.setEndpoint(cfg != null ? cfg.getEndpoint() : null);
        if (StringUtils.isBlank(request.getModelCode()))
            request.setModelCode(cfg != null ? cfg.getModelCode() : null);
        if (StringUtils.isBlank(request.getApiKey())) {
            log.error("[Minimax] apiKey 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        if (StringUtils.isBlank(request.getEndpoint())) {
            log.error("[Minimax] endpoint 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        if (StringUtils.isBlank(request.getModelCode())) {
            log.error("[Minimax] modelCode 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
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
        if (request.getTopK() != null) {
            body.put("top_k", request.getTopK());
        }
        if (request.getFrequencyPenalty() != null) {
            body.put("frequency_penalty", request.getFrequencyPenalty());
        }
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            body.put("tools", request.getTools());
        }
        if (request.getToolChoice() != null) {
            body.put("tool_choice", buildToolChoice(request.getToolChoice()));
        }
        if (request.getExtraParams() != null) {
            request.getExtraParams().forEach((k, v) -> {
                if (!"tools".equals(k) && !"tool_choice".equals(k)) body.put(k, v);
            });
        }
        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    /**
     * 构造 tool_choice 字段：auto/none 原样透传字符串；其余值视为指定工具名，转为 OpenAI 兼容协议要求的对象结构。
     */
    private Object buildToolChoice(String toolChoice) {
        if ("auto".equals(toolChoice) || "none".equals(toolChoice)) {
            return toolChoice;
        }
        return Map.of("type", "function", "function", Map.of("name", toolChoice));
    }

    /**
     * Minimax 特有：每条消息需带 name 字段。
     * role=system → name="assistant"
     * role=user   → name="user"
     * role=assistant → name="MM智能助手"
     * role=tool   → name="tool"
     */
    private List<Object> buildMessages(LlmRequest request) {
        List<Object> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("name", resolveMinimaxName(msg.getRole()));
            if (msg.getToolCalls() != null) {
                String text = msg.getTextContent();
                m.put("content", text.isEmpty() ? null : text);
                m.put("tool_calls", buildToolCallsArray(msg.getToolCalls()));
            } else if (msg.getToolCallId() != null) {
                m.put("tool_call_id", msg.getToolCallId());
                m.put("content", msg.getTextContent());
            } else if (msg.isTextOnly()) {
                m.put("content", msg.getTextContent());
            } else {
                m.put("content", buildContentArray(msg));
            }
            messages.add(m);
        }
        return messages;
    }

    private String resolveMinimaxName(String role) {
        return switch (role) {
            case "user"      -> "user";
            case "assistant" -> "MM智能助手";
            case "tool"      -> "tool";
            default          -> "assistant";
        };
    }

    private List<Map<String, Object>> buildToolCallsArray(List<LlmToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (LlmToolCall tc : toolCalls) {
            Map<String, Object> function = new HashMap<>();
            function.put("name", tc.getName());
            function.put("arguments", tc.getArguments());
            Map<String, Object> toolCall = new HashMap<>();
            toolCall.put("id", tc.getId());
            toolCall.put("type", "function");
            toolCall.put("function", function);
            result.add(toolCall);
        }
        return result;
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
        return new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(requestBody, JSON))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .build();
    }

    // ==================== 响应解析 ====================

    /**
     * Minimax 特有：HTTP 200 时仍需检查 base_resp.status_code，非 0 为业务错误。
     */
    private void checkBaseResp(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode baseResp = root.path("base_resp");
            if (!baseResp.isMissingNode()) {
                int code = baseResp.path("status_code").asInt(0);
                if (code != 0) {
                    String msg = baseResp.path("status_msg").asText("Minimax business error");
                    log.error("[Minimax-chat] 业务错误 base_resp.status_code={}, msg={}", code, msg);
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED, msg);
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            // 解析失败忽略，交由 parseResponse 处理
        }
    }

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
                        .toolCalls(parseToolCalls(msg.path("tool_calls")))
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
            log.error("[Minimax-chat] 响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    /**
     * 解析 message.tool_calls 数组；节点不存在或为空数组时返回 null（对应普通对话，无工具调用）。
     */
    private List<LlmToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || toolCallsNode.isMissingNode() || !toolCallsNode.isArray() || toolCallsNode.isEmpty()) {
            return null;
        }
        List<LlmToolCall> result = new ArrayList<>();
        for (JsonNode tc : toolCallsNode) {
            JsonNode function = tc.path("function");
            result.add(LlmToolCall.builder()
                    .id(tc.path("id").asText(null))
                    .name(function.path("name").asText(null))
                    .arguments(function.path("arguments").asText(null))
                    .build());
        }
        return result;
    }

    private void parseStreamResponse(ResponseBody responseBody, String modelCode, Consumer<String> chunkConsumer) {
        Map<Integer, String[]> toolCallsMap = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SSE_DATA_PREFIX)) continue;
                String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (SSE_DONE_FLAG.equals(data)) {
                    flushToolCalls(toolCallsMap, modelCode, chunkConsumer);
                    chunkConsumer.accept(null);
                    return;
                }
                JsonNode delta = MAPPER.readTree(data).path("choices").path(0).path("delta");
                String chunk = delta.path("content").asText("");
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }
                JsonNode toolCallsNode = delta.path("tool_calls");
                if (toolCallsNode.isArray()) {
                    for (JsonNode tc : toolCallsNode) {
                        int index = tc.path("index").asInt(0);
                        String[] entry = toolCallsMap.computeIfAbsent(index, k -> new String[]{"", "", ""});
                        if (!tc.path("id").asText("").isEmpty()) entry[0] = tc.path("id").asText("");
                        JsonNode fn = tc.path("function");
                        if (!fn.path("name").asText("").isEmpty()) entry[1] = fn.path("name").asText("");
                        entry[2] += fn.path("arguments").asText("");
                    }
                }
            }
            // 流正常读完但没有收到 [DONE] 帧（Minimax 服务端偶发），兜底关闭 SSE 连接
            flushToolCalls(toolCallsMap, modelCode, chunkConsumer);
            chunkConsumer.accept(null);
        } catch (IOException e) {
            log.error("[Minimax-stream] 流式响应解析失败, model={}", modelCode, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    private void flushToolCalls(Map<Integer, String[]> toolCallsMap, String modelCode, Consumer<String> chunkConsumer) {
        if (toolCallsMap.isEmpty()) return;
        try {
            List<Map<String, String>> list = new ArrayList<>();
            for (String[] entry : toolCallsMap.values()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("id", entry[0]);
                item.put("name", entry[1]);
                item.put("arguments", entry[2]);
                list.add(item);
            }
            chunkConsumer.accept("[TOOL_CALLS]" + MAPPER.writeValueAsString(list));
        } catch (IOException e) {
            log.warn("[Minimax-stream] tool_calls 序列化失败，跳过, model={}", modelCode, e);
        }
    }

    // ==================== 工具方法 ====================

    private void throwByHttpCode(int httpCode, String platformMsg) {
        // Minimax: 错误码与 OpenAI 一致，另有业务错误通过 base_resp.status_code 商定
        ErrorCodeEnum errorCode;
        if (httpCode == MinimaxHttpCodeEnum.UNAUTHORIZED.getCode()) {
            errorCode = ErrorCodeEnum.LLM_AUTH_FAILED;
        } else if (httpCode == MinimaxHttpCodeEnum.BAD_REQUEST.getCode()
                || httpCode == MinimaxHttpCodeEnum.UNPROCESSABLE.getCode()) {
            errorCode = ErrorCodeEnum.PARAM_ILLEGAL;
        } else if (httpCode == MinimaxHttpCodeEnum.RATE_LIMIT.getCode()) {
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
            if (msg.isEmpty()) {
                // Minimax 业务错误在 base_resp.status_msg
                msg = root.path("base_resp").path("status_msg").asText("");
            }
            return msg.isEmpty() ? truncate(responseBody) : msg;
        } catch (Exception e) {
            return truncate(responseBody);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

}

