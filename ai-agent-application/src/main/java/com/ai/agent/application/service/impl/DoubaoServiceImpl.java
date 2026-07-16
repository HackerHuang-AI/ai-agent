package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.DoubaoBO;
import com.ai.agent.application.enums.http.DoubaoHttpCode;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.ai.agent.infrastructure.config.RetryConfig;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.ai.agent.infrastructure.utils.RetryUtil;
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

    public DoubaoServiceImpl(@Qualifier("doubaoStreamExecutor") ExecutorService streamExecutor,
            OkHttpConfig okHttpConfig,
            RetryConfig retryConfig) {
        this.streamExecutor = streamExecutor;
        this.okHttpConfig = okHttpConfig;
        this.retryConfig = retryConfig;
    }

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG = "[DONE]";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExecutorService streamExecutor;
    private final OkHttpConfig okHttpConfig;
    private final RetryConfig retryConfig;

    @Override
    public LlmResponse chat(LlmRequest request) {
        fillChatDefaults(request);
        log.info("[Doubao-chat] 开始调用, request={}", request);
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();
        LlmResponse result = RetryUtil.retry(() -> {
            Request okRequest = new Request.Builder()
                    .url(request.getEndpoint())
                    .post(RequestBody.create(requestBody, JSON))
                    .headers(Headers.of(buildHeaders(request.getApiKey())))
                    .build();
            try (Response response = okHttpConfig.getLlmClient("doubao").newCall(okRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String platformMsg = extractErrorMessage(responseBody);
                    log.error("[Doubao-chat] HTTP {} 失败, platformError={}", response.code(), platformMsg);
                    throwByHttpCode(response.code(), platformMsg);
                }
                if (responseBody.isEmpty()) {
                    log.error("[Doubao-chat] 响应体为空");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                return parseResponse(responseBody, request.getModelCode());
            } catch (BizException e) {
                throw e;
            } catch (IOException e) {
                log.error("[Doubao-chat] IO 异常", e);
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            }
        }, retryConfig.getRetryParam("doubao"));
        if (result == null) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        log.info("[Doubao-chat] 调用成功, result={}, costMs={}",
                result, System.currentTimeMillis() - start);
        return result;
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        fillChatDefaults(request);
        String requestBody = buildRequestBody(request, true);
        log.info("[Doubao-stream] 开始调用, request={}", request);
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

                    try (Response response = okHttpConfig.getLlmClient("doubao").newCall(okRequest).execute()) {
                        if (!response.isSuccessful()) {
                            String errBody = response.body() != null ? response.body().string() : "";
                            String platformMsg = extractErrorMessage(errBody);
                            log.error("[Doubao-stream] HTTP {} 失败, platformError={}", response.code(), platformMsg);
                            throwByHttpCode(response.code(), platformMsg);
                        }
                        if (response.body() == null) {
                            log.error("[Doubao-stream] 响应体为空");
                            chunkConsumer.accept("[ERROR]");
                            return;
                        }
                        parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                    }
                } catch (BizException e) {
                    log.error("[Doubao-stream] 业务异常", e);
                    chunkConsumer.accept("[ERROR]");
                } catch (IOException e) {
                    log.error("[Doubao-stream] IO 异常", e);
                    chunkConsumer.accept("[ERROR]");
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Doubao-stream] 线程池已满，拒绝请求", e);
            chunkConsumer.accept("[ERROR]");
        }
    }

    /**
     * 统一接口入口：将 LlmRequest 中的 messages 转换为 Responses API input 格式后调用。
     * 覆盖 LlmService 的 default 实现，豆包支持多模态。
     */
    @Override
    public LlmResponse multimodalChat(LlmRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "messages 不能为空");
        }
        List<Map<String, Object>> input = request.getMessages().stream().map(msg -> {
            if (msg.getContents() == null || msg.getContents().isEmpty()) {
                throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "多模态消息的 contents 不能为空");
            }
            List<Map<String, Object>> contentList = msg.getContents().stream()
                    .map(c -> {
                        if (c.getType() == null || c.getValue() == null || c.getValue().isBlank()) {
                            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "contents 中 type/value 不能为空");
                        }
                        Map<String, Object> item = new LinkedHashMap<>();
                        switch (c.getType()) {
                            case IMAGE -> { item.put("type", "input_image"); item.put("image_url", c.getValue()); }
                            case TEXT  -> { item.put("type", "input_text");  item.put("text", c.getValue()); }
                            default    -> throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL,
                                    "豆包多模态暂不支持 " + c.getType() + " 类型");
                        }
                        return item;
                    })
                    .collect(java.util.stream.Collectors.toList());
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", msg.getRole());
            message.put("content", contentList);
            return message;
        }).collect(java.util.stream.Collectors.toList());
        return multimodalChat(request.getModelCode(), input, request.getApiKey(), request.getEndpoint());
    }

    /**
     * 多模态对话（Responses API）
     * 支持图片+文本混合输入，调用 /v3/responses 协议
     */
    public LlmResponse multimodalChat(String model, List<Map<String, Object>> input, String apiKey, String endpoint) {
        // 第一步：只在有字段为空时才读 Nacos
        DoubaoBO cfg = null;
        if (StringUtils.isBlank(apiKey) || StringUtils.isBlank(endpoint) || StringUtils.isBlank(model)) {
            cfg = getMultimodalConfig();
        }
        // 第二步：入参为空时用 Nacos 补
        if (StringUtils.isBlank(apiKey))   apiKey   = cfg != null ? cfg.getApiKey()   : null;
        if (StringUtils.isBlank(endpoint)) endpoint = cfg != null ? cfg.getEndpoint() : null;
        if (StringUtils.isBlank(model))    model    = cfg != null ? cfg.getModel()    : null;
        // 第三步：补完后校验必填项
        if (StringUtils.isBlank(apiKey)) {
            log.error("[Doubao-multimodal] apiKey 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        if (StringUtils.isBlank(endpoint)) {
            log.error("[Doubao-multimodal] endpoint 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        if (StringUtils.isBlank(model)) {
            log.error("[Doubao-multimodal] model 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        // ⚠️ 豆包 Responses API（/v3/responses）与 Chat Completions（/v3/chat/completions）是两套不同协议：
        //   - Chat Completions 支持 temperature / top_p / top_k / max_tokens / frequency_penalty / presence_penalty
        //   - Responses API 仅支持 model + input，不接受生成参数，如需控制生成行为，请改用 /chat 接口
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

            try (Response response = okHttpConfig.getLlmClient("doubao").newCall(okRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String platformMsg = extractErrorMessage(responseBody);
                    log.error("[Doubao-multimodal] HTTP {} 失败, platformError={}", response.code(), platformMsg);
                    throwByHttpCode(response.code(), platformMsg);
                }
                if (responseBody.isEmpty()) {
                    log.error("[Doubao-multimodal] 响应体为空");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                LlmResponse result = parseMultimodalResponse(responseBody, model);
                log.info("[Doubao-multimodal] 调用成功, inputTokens={}, outputTokens={}, costMs={}",
                        result.getUsage().getInputTokens(), result.getUsage().getOutputTokens(),
                        System.currentTimeMillis() - start);
                return result;
            }
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao-multimodal] IO 异常", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    /**
     * 多模态对话（form-data 文件上传）
     * 接受原始图片字节，内部完成 base64 编码和 input 结构组装
     */
    public LlmResponse multimodalChatFile(byte[] imageBytes, String mimeType, String text,
                                          String model, String apiKey, String endpoint) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String imageUrl = "data:" + mimeType + ";base64," + base64;

        List<Map<String, Object>> input = List.of(Map.of(
                "role", "user",
                "content", List.of(
                        Map.of("type", "input_image", "image_url", imageUrl),
                        Map.of("type", "input_text", "text", text)
                )
        ));
        return multimodalChat(model, input, apiKey, endpoint);
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
        // 豆包平台私有参数（通过 extraParams 透传），合并到请求体最外层。
        // 支持的私有参数包括：
        //   top_k            - Top-K 采样，限制每步候选词数量，0 表示不限制；范围 [0, ∞)
        //   frequency_penalty - 频率惩罚，按词出现次数累加惩罚，降低重复率；范围 [-2, 2]
        //   presence_penalty  - 存在惩罚，只要出现过就施加固定惩罚，鼓励话题多样性；范围 [-2, 2]
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
            log.error("[Doubao-chat] 响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    // ==================== Nacos 兜底 ====================

    /**
     * chat / chatStream 入参兜底：用户传了用用户的，没传从 Nacos chat 配置补，补完仍空则 log + 抛参数异常。
     */
    private void fillChatDefaults(LlmRequest request) {
        // 第一步：只在有字段为空时才读 Nacos（避免不必要的 IO）
        DoubaoBO cfg = null;
        if (StringUtils.isBlank(request.getApiKey())
                || StringUtils.isBlank(request.getEndpoint())
                || StringUtils.isBlank(request.getModelCode())) {
            cfg = getChatConfig();
        }
        // 第二步：入参为空时用 Nacos 补
        if (StringUtils.isBlank(request.getApiKey()))
            request.setApiKey(cfg != null ? cfg.getApiKey() : null);
        if (StringUtils.isBlank(request.getEndpoint()))
            request.setEndpoint(cfg != null ? cfg.getEndpoint() : null);
        if (StringUtils.isBlank(request.getModelCode()))
            request.setModelCode(cfg != null ? cfg.getEndpointId() : null);
        // 第三步：补完后校验必填项
        if (StringUtils.isBlank(request.getApiKey())) {
            log.error("[Doubao-chat] apiKey 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        if (StringUtils.isBlank(request.getEndpoint())) {
            log.error("[Doubao-chat] endpoint 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        if (StringUtils.isBlank(request.getModelCode())) {
            log.error("[Doubao-chat] endpointId 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    private DoubaoBO getChatConfig() {
        return NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_DOUBAO, "chat", DoubaoBO.class);
    }

    private DoubaoBO getMultimodalConfig() {
        return NacosConfigUtil.getObject(NacosDataIdEnum.AI_AGENT_DOUBAO, "multimodal", DoubaoBO.class);
    }

    /**
     * 按火山方舟 HTTP 错误码路由到对应业务异常。
     * 401 → 认证失败；400/422 → 参数错误；429 → 限速；其余 → 通用调用失败。
     * 注：火山方舟无独立 402 余额不足码，余额问题通常通过 400 + error.code 区分。
     */
    private void throwByHttpCode(int httpCode, String platformMsg) {
        ErrorCodeEnum errorCode;
        if (httpCode == DoubaoHttpCode.UNAUTHORIZED.getCode()) {
            errorCode = ErrorCodeEnum.LLM_AUTH_FAILED;
        } else if (httpCode == DoubaoHttpCode.BAD_REQUEST.getCode()
                || httpCode == DoubaoHttpCode.UNPROCESSABLE.getCode()) {
            errorCode = ErrorCodeEnum.PARAM_ILLEGAL;
        } else if (httpCode == DoubaoHttpCode.RATE_LIMIT.getCode()) {
            errorCode = ErrorCodeEnum.LLM_RATE_LIMIT;
        } else {
            errorCode = ErrorCodeEnum.LLM_CALL_FAILED;
        }
        throw new BizException(errorCode, platformMsg);
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                String msg = error.path("message").asText("");
                String code = error.path("code").asText("");
                return msg.isEmpty() ? truncate(responseBody) : (code.isEmpty() ? msg : "[" + code + "] " + msg);
            }
        } catch (Exception ignored) {
        }
        return truncate(responseBody);
    }

    private static String truncate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private LlmResponse parseMultimodalResponse(String responseJson, String model) {
        try {
            log.debug("[Doubao-multimodal] 原始响应: {}", responseJson);
            JsonNode root = MAPPER.readTree(responseJson);
            JsonNode usage = root.path("usage");
            int input  = usage.path("input_tokens").asInt(0);
            int output = usage.path("output_tokens").asInt(0);

            // 解析 output[]：遇到 type=reasoning 填 summary，遇到 type=message 填 content
            List<LlmOutputItem> outputItems = new ArrayList<>();
            for (JsonNode item : root.path("output")) {
                String type   = item.path("type").asText("");
                String status = item.path("status").asText(null);
                String id     = item.path("id").asText(null);

                if ("reasoning".equals(type)) {
                    List<LlmContentBlock> summaryBlocks = new ArrayList<>();
                    for (JsonNode s : item.path("summary")) {
                        summaryBlocks.add(LlmContentBlock.builder()
                                .type(s.path("type").asText(""))
                                .text(s.path("text").asText(""))
                                .build());
                    }
                    outputItems.add(LlmOutputItem.builder()
                            .id(id).type(type).status(status)
                            .summary(summaryBlocks)
                            .build());
                } else if ("message".equals(type)) {
                    List<LlmContentBlock> contentBlocks = new ArrayList<>();
                    for (JsonNode c : item.path("content")) {
                        contentBlocks.add(LlmContentBlock.builder()
                                .type(c.path("type").asText(""))
                                .text(c.path("text").asText(""))
                                .build());
                    }
                    outputItems.add(LlmOutputItem.builder()
                            .id(id).type(type).status(status)
                            .role(item.path("role").asText(null))
                            .content(contentBlocks)
                            .build());
                }
            }
            if (outputItems.stream().noneMatch(o -> "message".equals(o.getType()))) {
                log.warn("[Doubao-multimodal] output 中未找到 type=message 节点，原始响应: {}", responseJson);
            }

            return LlmResponse.builder()
                    .requestId(root.path("id").asText(null))
                    .modelCode(model)
                    .createdAt(root.path("created_at").asLong(0) > 0 ? root.path("created_at").asLong() : null)
                    .status(root.path("status").asText(null))
                    .maxOutputTokens(root.path("max_output_tokens").asInt(0) > 0 ? root.path("max_output_tokens").asInt() : null)
                    .output(outputItems)
                    .usage(LlmUsage.builder()
                            .inputTokens(input)
                            .outputTokens(output)
                            .totalTokens(!usage.path("total_tokens").isMissingNode() ? usage.path("total_tokens").asInt() : input + output)
                            .inputTokensDetails(!usage.path("input_tokens_details").isMissingNode()
                                    ? LlmInputTokensDetails.builder()
                                            .cachedTokens(usage.path("input_tokens_details").path("cached_tokens").asInt(0))
                                            .build()
                                    : null)
                            .outputTokensDetails(!usage.path("output_tokens_details").isMissingNode()
                                    ? LlmOutputTokensDetails.builder()
                                            .reasoningTokens(usage.path("output_tokens_details").path("reasoning_tokens").asInt(0))
                                            .build()
                                    : null)
                            .build())
                    .build();
        } catch (IOException e) {
            log.error("[Doubao-multimodal] 响应解析失败", e);
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
            // 流正常读完但未收到 [DONE] 帧（服务端偶发），兜底关闭 SSE 连接
            chunkConsumer.accept(null);
        } catch (IOException e) {
            log.error("[Doubao-stream] 流式响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    // ==================== 模型列表 ====================

    /**
     * 查询豆包平台支持的模型列表。
     * 调用火山方舟 GET /api/v3/models 接口，返回所有已部署模型的 id 列表。
     * apiKey / endpoint 为空时从 Nacos ai-agent-doubao.json chat 块兜底。
     */
    @Override
    public List<LlmModelInfo> listModels(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            DoubaoBO cfg = getChatConfig();
            apiKey = cfg != null ? cfg.getApiKey() : null;
        }
        if (StringUtils.isBlank(apiKey)) {
            log.error("[Doubao-models] apiKey 未配置");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }

        String modelsUrl = "https://ark.cn-beijing.volces.com/api/v3/models";
        Request okRequest = new Request.Builder()
                .url(modelsUrl)
                .get()
                .header("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = okHttpConfig.getLlmClient("doubao").newCall(okRequest).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("[Doubao-models] HTTP {} 失败, body={}", response.code(), truncate(body));
                throwByHttpCode(response.code(), extractErrorMessage(body));
            }
            JsonNode root = MAPPER.readTree(body);
            List<LlmModelInfo> result = new ArrayList<>();
            for (JsonNode item : root.path("data")) {
                result.add(parseDoubaoModel(item));
            }
            log.info("[Doubao-models] 获取模型列表成功, count={}", result.size());
            return result;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao-models] IO 异常", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    private LlmModelInfo parseDoubaoModel(JsonNode item) {
        // token_limits
        JsonNode tokenLimits = item.path("token_limits");
        Integer contextWindow  = tokenLimits.isMissingNode() ? null : tokenLimits.path("context_window").asInt(0);
        Integer maxInputTokens = tokenLimits.isMissingNode() ? null : tokenLimits.path("max_input_token_length").asInt(0);
        Integer maxOutputTokens= tokenLimits.isMissingNode() ? null : tokenLimits.path("max_output_token_length").asInt(0);

        // modalities
        JsonNode modalities = item.path("modalities");
        List<String> inputModalities  = jsonArrayToList(modalities.path("input_modalities"));
        List<String> outputModalities = jsonArrayToList(modalities.path("output_modalities"));

        // features
        JsonNode features       = item.path("features");
        JsonNode batch          = features.path("batch");
        JsonNode structuredOut  = features.path("structured_outputs");
        JsonNode tools          = features.path("tools");
        Boolean supportBatch       = batch.isMissingNode()         ? null : (batch.path("batch_chat").asBoolean(false) || batch.path("batch_job").asBoolean(false));
        Boolean supportJsonObject  = structuredOut.isMissingNode() ? null : structuredOut.path("json_object").asBoolean(false);
        Boolean supportJsonSchema  = structuredOut.isMissingNode() ? null : structuredOut.path("json_schema").asBoolean(false);
        Boolean supportFuncCall    = tools.isMissingNode()         ? null : tools.path("function_calling").asBoolean(false);

        // task_type
        List<String> taskTypes = jsonArrayToList(item.path("task_type"));

        long created = item.path("created").asLong(0);

        return LlmModelInfo.builder()
                .id(item.path("id").asText(null))
                .name(item.path("name").asText(null))
                .ownedBy(item.path("owned_by").asText(null))
                .created(created > 0 ? created : null)
                .status(item.path("status").asText(null))
                .domain(item.path("domain").asText(null))
                .version(item.path("version").asText(null))
                .taskTypes(taskTypes.isEmpty() ? null : taskTypes)
                .inputModalities(inputModalities.isEmpty() ? null : inputModalities)
                .outputModalities(outputModalities.isEmpty() ? null : outputModalities)
                .contextWindow(contextWindow)
                .maxInputTokens(maxInputTokens)
                .maxOutputTokens(maxOutputTokens)
                .supportBatch(supportBatch)
                .supportJsonObject(supportJsonObject)
                .supportJsonSchema(supportJsonSchema)
                .supportFunctionCalling(supportFuncCall)
                .build();
    }

    private List<String> jsonArrayToList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String v = n.asText("");
                if (!v.isEmpty()) list.add(v);
            }
        }
        return list;
    }
}

