package com.ai.agent.infrastructure.llm.service;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.infrastructure.utils.JsonUtil;
import com.ai.agent.infrastructure.utils.OkHttpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * @Description: 豆包（火山方舟）平台调用服务
 *               使用 OpenAI 兼容协议，差异点：
 *               1. model 字段传 endpointId（火山方舟控制台的模型接入点 ID，如 ep-xxxxxxxx）
 *               2. 仅支持纯文本对话，不支持多模态内容
 *               3. Endpoint：https://ark.cn-beijing.volces.com/api/v3/chat/completions
 *               4. Authorization 同样使用 Bearer Token
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.llm.service
 * @ClassName: DoubaoService
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Slf4j
@Service
public class DoubaoService implements LlmPlatformService {

    private static final String PLATFORM_CODE = "DOUBAO";

    private static final String SSE_DATA_PREFIX = "data: ";
    private static final String SSE_DONE_FLAG = "[DONE]";

    @Override
    public String platformCode() {
        return PLATFORM_CODE;
    }

    @Override
    public LlmResponse chat(LlmRequest request, String apiKey, String endpoint) {
        String requestBody = buildRequestBody(request, false);
        Map<String, String> headers = buildHeaders(apiKey);

        log.info("[Doubao] 同步调用开始, endpointId={}, endpoint={}", request.getModelCode(), endpoint);
        long start = System.currentTimeMillis();

        try {
            RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8"));
            Request okRequest = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .headers(Headers.of(headers))
                    .build();

            Response okResponse = OkHttpUtil.getLlmClient().newCall(okRequest).execute();
            String responseJson;
            try (okResponse) {
                if (!okResponse.isSuccessful() || okResponse.body() == null) {
                    log.error("[Doubao] 同步调用 HTTP 失败, endpointId={}, code={}, body={}",
                            request.getModelCode(), okResponse.code(),
                            okResponse.body() != null ? okResponse.body().string() : "null");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                responseJson = okResponse.body().string();
            }

            LlmResponse response = parseResponse(responseJson, request.getModelCode());
            log.info("[Doubao] 同步调用成功, endpointId={}, inputTokens={}, outputTokens={}, costMs={}",
                    request.getModelCode(), response.getInputTokens(), response.getOutputTokens(),
                    System.currentTimeMillis() - start);
            return response;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao] 同步调用 IO 异常, endpointId={}", request.getModelCode(), e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    @Override
    public void chatStream(LlmRequest request, String apiKey, String endpoint, Consumer<String> chunkConsumer) {
        String requestBody = buildRequestBody(request, true);
        Map<String, String> headers = buildHeaders(apiKey);

        RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8"));
        Request okRequest = new Request.Builder()
                .url(endpoint)
                .post(body)
                .headers(Headers.of(headers))
                .build();

        log.info("[Doubao] 流式调用开始, endpointId={}, endpoint={}", request.getModelCode(), endpoint);

        try (Response response = OkHttpUtil.getLlmClient().newCall(okRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.error("[Doubao] 流式调用 HTTP 失败, endpointId={}, code={}", request.getModelCode(), response.code());
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            }
            parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao] 流式调用 IO 异常, endpointId={}", request.getModelCode(), e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    /**
     * 多模态对话（Responses API）
     * 支持图片+文本混合输入，调用 /v3/responses 协议
     *
     * @param model    模型接入点 ID
     * @param input    消息列表（已序列化为 Map，包含 role + content 数组）
     * @param apiKey   API Key
     * @param endpoint Responses API 地址，如 https://ark.cn-beijing.volces.com/api/v3/responses
     */
    public LlmResponse multimodalChat(String model, List<Map<String, Object>> input, String apiKey, String endpoint) {
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("model", model);
        bodyMap.put("input", input);

        String requestBody;
        try {
            requestBody = JsonUtil.toJson(bodyMap);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }

        Map<String, String> headers = buildHeaders(apiKey);
        log.info("[Doubao-Multimodal] 调用开始, model={}, endpoint={}", model, endpoint);
        long start = System.currentTimeMillis();

        try {
            RequestBody body = RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8"));
            Request okRequest = new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .headers(Headers.of(headers))
                    .build();

            Response okResponse = OkHttpUtil.getLlmClient().newCall(okRequest).execute();
            String responseJson;
            try (okResponse) {
                if (!okResponse.isSuccessful() || okResponse.body() == null) {
                    log.error("[Doubao-Multimodal] HTTP 失败, model={}, code={}, body={}",
                            model, okResponse.code(),
                            okResponse.body() != null ? okResponse.body().string() : "null");
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                responseJson = okResponse.body().string();
            }

            LlmResponse response = parseMultimodalResponse(responseJson, model);
            log.info("[Doubao-Multimodal] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                    model, response.getInputTokens(), response.getOutputTokens(),
                    System.currentTimeMillis() - start);
            return response;
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[Doubao-Multimodal] IO 异常, model={}", model, e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 构建豆包请求体
     * model 字段传 endpointId（modelCode 字段复用存储 endpointId）
     * 仅支持文本，content 直接传字符串
     */
    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new HashMap<>();
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
            return JsonUtil.toJson(body);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    /**
     * 构建消息列表，豆包仅支持文本，强制取文本内容
     */
    private List<Object> buildMessages(LlmRequest request) {
        List<Object> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            if (!msg.isTextOnly()) {
                throw new BizException(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
            }
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getTextContent());
            messages.add(m);
        }
        return messages;
    }

    private Map<String, String> buildHeaders(String apiKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    private LlmResponse parseResponse(String responseJson, String modelCode) {
        try {
            JsonNode root = JsonUtil.readTree(responseJson);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");

            String content = message.path("content").asText("");
            String finishReason = choice.path("finish_reason").asText("");

            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);

            return LlmResponse.builder()
                    .content(content)
                    .modelCode(modelCode)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .finishReason(finishReason)
                    .build();
        } catch (IOException e) {
            log.error("[Doubao] 响应解析失败, endpointId={}, response={}", modelCode, responseJson, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    /**
     * 解析 Responses API 响应
     * 响应结构：output[].content[].text
     */
    private LlmResponse parseMultimodalResponse(String responseJson, String model) {
        try {
            JsonNode root = JsonUtil.readTree(responseJson);

            // 取 output 数组第一个 message 的第一个 content 块的 text
            String content = root.path("output").path(0).path("content").path(0).path("text").asText("");

            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);

            return LlmResponse.builder()
                    .content(content)
                    .modelCode(model)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .finishReason("stop")
                    .build();
        } catch (IOException e) {
            log.error("[Doubao-Multimodal] 响应解析失败, model={}, response={}", model, responseJson, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    private void parseStreamResponse(ResponseBody responseBody, String modelCode, Consumer<String> chunkConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SSE_DATA_PREFIX)) {
                    continue;
                }
                String data = line.substring(SSE_DATA_PREFIX.length()).trim();
                if (SSE_DONE_FLAG.equals(data)) {
                    chunkConsumer.accept(null);
                    return;
                }
                JsonNode root = JsonUtil.readTree(data);
                JsonNode delta = root.path("choices").path(0).path("delta");

                String chunk = delta.path("content").asText("");
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }
            }
        } catch (IOException e) {
            log.error("[Doubao] 流式响应解析失败, endpointId={}", modelCode, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }
}

