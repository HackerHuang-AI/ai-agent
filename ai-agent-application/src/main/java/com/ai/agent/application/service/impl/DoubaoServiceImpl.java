package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.DoubaoConfig;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.infrastructure.config.NacosDataId;
import com.ai.agent.infrastructure.utils.NacosConfigUtil;
import com.ai.agent.infrastructure.utils.OkHttpUtil;
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

    public DoubaoServiceImpl(@Qualifier("doubaoStreamExecutor") ExecutorService streamExecutor) {
        this.streamExecutor = streamExecutor;
    }

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG = "[DONE]";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = OkHttpUtil.getLlmClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExecutorService streamExecutor;

    @Override
    public LlmResponse chat(LlmRequest request) {
        fillChatDefaults(request);
        log.info("[Doubao-chat] 开始调用, request={}", request);
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();
        Request okRequest = new Request.Builder()
                .url(request.getEndpoint())
                .post(RequestBody.create(requestBody, JSON))
                .headers(Headers.of(buildHeaders(request.getApiKey())))
                .build();

        try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                String platformMsg = extractErrorMessage(responseBody);
                log.error("[Doubao-chat] HTTP 失败, httpStatus={}, platformError={}", response.code(), platformMsg);
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED, platformMsg);
            }
            if (responseBody.isEmpty()) {
                log.error("[Doubao-chat] 响应体为空");
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            }
            LlmResponse result = parseResponse(responseBody, request.getModelCode());
            log.info("[Doubao-chat] 调用成功, result={}, costMs={}",
                    result, System.currentTimeMillis() - start);
            return result;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao-chat] IO 异常", e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        fillChatDefaults(request);
        String requestBody = buildRequestBody(request, true);
        log.info("[Doubao-stream] 开始调用, request={}", request);
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();

        streamExecutor.submit(() -> {
            if (mdcContext != null) MDC.setContextMap(mdcContext);
            try {
                Request okRequest = new Request.Builder()
                        .url(request.getEndpoint())
                        .post(RequestBody.create(requestBody, JSON))
                        .headers(Headers.of(buildHeaders(request.getApiKey())))
                        .build();

                try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        String errBody = response.body() != null ? response.body().string() : "";
                        log.error("[Doubao-stream] HTTP 失败, httpStatus={}, platformError={}",
                                response.code(), extractErrorMessage(errBody));
                        chunkConsumer.accept(null);
                        return;
                    }
                    parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                }
            } catch (BizException e) {
                log.error("[Doubao-stream] 业务异常", e);
                chunkConsumer.accept(null);
            } catch (IOException e) {
                log.error("[Doubao-stream] IO 异常", e);
                chunkConsumer.accept(null);
            } finally {
                MDC.clear();
            }
        });
    }

    /**
     * 多模态对话（Responses API）
     * 支持图片+文本混合输入，调用 /v3/responses 协议
     */
    public LlmResponse multimodalChat(String model, List<Map<String, Object>> input, String apiKey, String endpoint) {
        // 第一步：只在有字段为空时才读 Nacos
        DoubaoConfig cfg = null;
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

            try (Response response = HTTP_CLIENT.newCall(okRequest).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String platformMsg = extractErrorMessage(responseBody);
                    log.error("[Doubao-multimodal] HTTP 失败, httpStatus={}, platformError={}", response.code(), platformMsg);
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED, platformMsg);
                }
                if (responseBody.isEmpty()) {
                    log.error("[Doubao-multimodal] 响应体为空");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                LlmResponse result = parseMultimodalResponse(responseBody, model);
                log.info("[Doubao-multimodal] 调用成功, inputTokens={}, outputTokens={}, costMs={}",
                        result.getInputTokens(), result.getOutputTokens(),
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
        DoubaoConfig cfg = null;
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

    private DoubaoConfig getChatConfig() {
        return NacosConfigUtil.getObject(NacosDataId.AI_AGENT_DOUBAO, "chat", DoubaoConfig.class);
    }

    private DoubaoConfig getMultimodalConfig() {
        return NacosConfigUtil.getObject(NacosDataId.AI_AGENT_DOUBAO, "multimodal", DoubaoConfig.class);
    }

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
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
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
        } catch (IOException e) {
            log.error("[Doubao-stream] 流式响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }
}

