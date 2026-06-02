package com.ai.agent.infrastructure.llm.adapter;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentType;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmMessage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.MessageContent;
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
 *
 *               多模态支持：
 *               - TEXT  → content 字符串（纯文本消息）
 *               - IMAGE → content 数组，type=image_url，url 支持 HTTP URL 或 base64 data URI
 *               - FILE  → content 数组，type=file，传 file_id 或 base64（gpt-4.1+）
 *               - VIDEO → 抛出不支持异常（OpenAI 无 video type，需走 file 通道上传）
 *
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
     * 构建 OpenAI Chat Completions 请求体，支持多模态 content
     */
    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", request.getModelCode());
        body.put("stream", stream);

        // 流式时携带 usage 统计
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
     * 将 LlmMessage 列表转换为 OpenAI messages 数组
     * 纯文本消息：content 为字符串
     * 多模态消息：content 为数组
     */
    private List<Object> buildMessages(LlmRequest request) {
        List<Object> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            Map<String, Object> m = new HashMap<>();
            m.put("role", msg.getRole());
            if (msg.isTextOnly()) {
                // 纯文本：content 直接用字符串（兼容性最好）
                m.put("content", msg.getTextContent());
            } else {
                // 多模态：content 用数组
                m.put("content", buildContentArray(msg));
            }
            messages.add(m);
        }
        return messages;
    }

    /**
     * 构建多模态 content 数组
     * TEXT  → {"type":"text","text":"..."}
     * IMAGE → {"type":"image_url","image_url":{"url":"...","detail":"auto"}}
     * FILE  → {"type":"file","file":{"filename":"...","file_data":"..."}}
     * VIDEO → 不支持，抛异常
     */
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
                case FILE -> {
                    // gpt-4.1+ 支持 file 类型，传 base64 data URI
                    Map<String, Object> fileObj = new HashMap<>();
                    fileObj.put("file_data", c.getValue());
                    if (c.getDetail() != null) {
                        fileObj.put("filename", c.getDetail());
                    }
                    Map<String, Object> part = new HashMap<>();
                    part.put("type", "file");
                    part.put("file", fileObj);
                    parts.add(part);
                }
                case VIDEO -> throw new BizException(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
                default -> throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
            }
        }
        return parts;
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
     * 解析同步响应 JSON
     * 兼容 deepseek-reasoner 的 reasoning_content 字段
     */
    private LlmResponse parseResponse(String responseJson, String modelCode) {
        try {
            JsonNode root = JsonUtil.readTree(responseJson);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = message.path("content").asText("");
            String finishReason = choice.path("finish_reason").asText("");

            // Deepseek-Reasoner 特有：思维链内容
            String reasoningContent = null;
            JsonNode reasoningNode = message.path("reasoning_content");
            if (!reasoningNode.isMissingNode() && !reasoningNode.isNull()) {
                reasoningContent = reasoningNode.asText("");
            }

            JsonNode usage = root.path("usage");
            int inputTokens = usage.path("prompt_tokens").asInt(0);
            int outputTokens = usage.path("completion_tokens").asInt(0);

            return LlmResponse.builder()
                    .content(content)
                    .modelCode(modelCode)
                    .inputTokens(inputTokens)
                    .outputTokens(outputTokens)
                    .finishReason(finishReason)
                    .reasoningContent(reasoningContent)
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
     * 兼容 deepseek-reasoner 的 reasoning_content chunk（先于 content 输出）
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
                    chunkConsumer.accept(null);
                    return;
                }
                JsonNode root = JsonUtil.readTree(data);
                JsonNode delta = root.path("choices").path(0).path("delta");

                // 普通文本 chunk
                String chunk = delta.path("content").asText("");
                if (!chunk.isEmpty()) {
                    chunkConsumer.accept(chunk);
                }

                // Deepseek-Reasoner 思维链 chunk（reasoning_content 先于 content 输出，此处忽略，调用方不感知）
                // 如需透传可扩展 chunkConsumer 为自定义回调接口
            }
        } catch (IOException e) {
            log.error("OpenAI 流式响应解析失败, model={}, error={}", modelCode, e.getMessage(), e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }
}

