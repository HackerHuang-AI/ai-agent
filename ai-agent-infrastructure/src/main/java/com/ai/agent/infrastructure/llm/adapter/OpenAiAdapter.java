package com.ai.agent.infrastructure.llm.adapter;

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
import org.springframework.stereotype.Component;

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
 * @Description: OpenAI 协议适配器
 *               兼容所有遵循 OpenAI Chat Completions API 协议的平台：
 *               OpenAI、Deepseek、Qwen、Minimax、智谱 GLM、Moonshot 等
 *               平台差异只体现在 endpoint 和 apiKey，协议格式完全一致。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.llm.adapter
 * @ClassName: OpenAiAdapter
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Slf4j
@Component
public class OpenAiAdapter implements LlmAdapter {

    private static final String PLATFORM_CODE = "OPENAI";

    // SSE 流式数据前缀
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

        log.info("OpenAI 同步调用开始, model={}, endpoint={}", request.getModelCode(), endpoint);
        long start = System.currentTimeMillis();

        try {
            // 使用 LLM 专用 Client（readTimeout=120s），避免大模型慢响应被通用 Client 的 15s 截断
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
                    log.error("OpenAI 同步调用 HTTP 失败, model={}, code={}", request.getModelCode(), okResponse.code());
                    throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                }
                responseJson = okResponse.body().string();
            }
            LlmResponse response = parseResponse(responseJson, request.getModelCode());
            log.info("OpenAI 同步调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                    request.getModelCode(), response.getInputTokens(), response.getOutputTokens(),
                    System.currentTimeMillis() - start);
            return response;
        } catch (IOException e) {
            log.error("OpenAI 同步调用失败, model={}, error={}", request.getModelCode(), e.getMessage(), e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    @Override
    public void chatStream(LlmRequest request, String apiKey, String endpoint, Consumer<String> chunkConsumer) {
        String requestBody = buildRequestBody(request, true);
        Map<String, String> headers = buildHeaders(apiKey);

        RequestBody body = RequestBody.create(requestBody,
                MediaType.parse("application/json; charset=utf-8"));
        Request okRequest = new Request.Builder()
                .url(endpoint)
                .post(body)
                .headers(Headers.of(headers))
                .build();

        log.info("OpenAI 流式调用开始, model={}, endpoint={}", request.getModelCode(), endpoint);

        try (Response response = OkHttpUtil.getLlmClient().newCall(okRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.error("OpenAI 流式调用 HTTP 失败, model={}, code={}", request.getModelCode(), response.code());
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            }
            parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("OpenAI 流式调用失败, model={}, error={}", request.getModelCode(), e.getMessage(), e);
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 构建 OpenAI Chat Completions 请求体
     */
    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.getModelCode());
        body.put("stream", stream);

        List<Map<String, String>> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            Map<String, String> m = new HashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            messages.add(m);
        }
        body.put("messages", messages);

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
     * 构建请求头：Authorization + Content-Type
     */
    private Map<String, String> buildHeaders(String apiKey) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Content-Type", "application/json");
        return headers;
    }

    /**
     * 解析同步响应 JSON，提取 content 和 token 统计
     * OpenAI 标准格式：
     * {
     *   "choices": [{"message": {"content": "..."}, "finish_reason": "stop"}],
     *   "usage": {"prompt_tokens": 10, "completion_tokens": 20}
     * }
     */
    private LlmResponse parseResponse(String responseJson, String modelCode) {
        try {
            JsonNode root = JsonUtil.readTree(responseJson);
            JsonNode choice = root.path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
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
            log.error("OpenAI 响应解析失败, model={}, response={}", modelCode, responseJson, e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    /**
     * 解析 SSE 流式响应
     * 每行格式：data: {"choices":[{"delta":{"content":"..."},"finish_reason":null}]}
     * 结束标志：data: [DONE]
     */
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
                    // 流结束，回调 null 通知调用方
                    chunkConsumer.accept(null);
                    return;
                }
                JsonNode root = JsonUtil.readTree(data);
                String chunk = root.path("choices").path(0)
                        .path("delta").path("content").asText("");
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }
            }
        } catch (IOException e) {
            log.error("OpenAI 流式响应解析失败, model={}, error={}", modelCode, e.getMessage(), e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }
}

