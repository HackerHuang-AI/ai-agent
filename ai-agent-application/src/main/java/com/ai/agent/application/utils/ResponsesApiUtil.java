package com.ai.agent.application.utils;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.common.TraceContextSupport;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.infrastructure.config.param.RetryParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Slf4j
public final class ResponsesApiUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponsesApiUtil() {}

    public static String buildRequestBody(LlmResponsesRequest request, boolean stream) {
        return buildRequestBody(request, stream, "text");
    }

    public static String buildRequestBody(LlmResponsesRequest request, boolean stream, String platform) {
        Object input = NativeInputUtil.responsesInput(request, platform);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("input", input);
        if (request.getInstructions() != null) body.put("instructions", request.getInstructions());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        if (request.getMaxOutputTokens() != null) body.put("max_output_tokens", request.getMaxOutputTokens());
        if (request.getTools() != null && !request.getTools().isEmpty()) body.put("tools", request.getTools());
        if (request.getToolChoice() != null) body.put("tool_choice", request.getToolChoice());
        if (request.getExtraParams() != null) {
            request.getExtraParams().forEach((key, value) -> {
                NativeInputUtil.putExtraParam(body, key, value);
            });
        }
        if (body.get("tool_choice") instanceof String choice && !List.of("auto", "none", "required").contains(choice)) {
            body.put("tool_choice", Map.of("type", "function", "name", choice));
        }
        body.put("stream", stream);
        try {
            return MAPPER.writeValueAsString(body);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    public static void validateStateless(LlmResponsesRequest request) {
        Map<String, Object> extra = request.getExtraParams();
        if (extra == null) return;
        if (extra.get("previous_response_id") != null || extra.get("conversation") != null
                || Boolean.TRUE.equals(extra.get("store")) || Boolean.TRUE.equals(extra.get("background"))) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "当前平台仅支持无状态 Responses，请通过 input 回传完整历史");
        }
    }

    public static void validateTools(LlmResponsesRequest request, List<String> allowedChoices) {
        Map<String, Object> extra = request.getExtraParams();
        Object tools = request.getTools() != null && !request.getTools().isEmpty() ? request.getTools()
                : extra != null ? extra.get("tools") : null;
        if (tools != null) {
            JsonNode nodes = MAPPER.valueToTree(tools);
            if (!nodes.isArray()) throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
            for (JsonNode tool : nodes) {
                if (!"function".equals(tool.path("type").asText())) {
                    throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "当前适配仅支持 function 工具");
                }
            }
        }
        Object choice = request.getToolChoice() != null ? request.getToolChoice()
                : extra != null ? extra.get("tool_choice") : null;
        if (choice == null) return;
        JsonNode node = MAPPER.valueToTree(choice);
        boolean named = allowedChoices.contains("required");
        if (node.isTextual() && (allowedChoices.contains(node.asText())
                || named && !node.asText().isBlank() && !List.of("auto", "none", "required").contains(node.asText()))) return;
        if (named && node.isObject() && "function".equals(node.path("type").asText())
                && !node.path("name").asText("").isBlank()) return;
        throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL, "当前平台不支持此 tool_choice");
    }

    public static LlmResponse parseResponse(String responseJson, String model) {
        try {
            JsonNode root = MAPPER.readTree(responseJson);
            if (root == null || !root.isObject() || !root.path("output").isArray()
                    || !root.path("error").isMissingNode() && !root.path("error").isNull()
                    || "failed".equals(root.path("status").asText())) {
                throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            }
            List<LlmOutputItem> items = new ArrayList<>();
            for (JsonNode item : root.path("output")) {
                String type = item.path("type").asText();
                if (!List.of("message", "reasoning", "function_call").contains(type)) {
                    throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
                }
                if ("message".equals(type) && !item.path("content").isArray()) {
                    throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
                }
                items.add(LlmOutputItem.builder().id(item.path("id").asText(null)).type(type)
                        .status(item.path("status").asText(null)).role(item.path("role").asText(null))
                        .callId(item.path("call_id").asText(null)).name(item.path("name").asText(null))
                        .arguments(item.path("arguments").asText(null)).encryptedContent(item.path("encrypted_content").asText(null))
                        .summary(blocks(item.path("summary"))).content(blocks(item.path("content"))).build());
            }
            JsonNode usage = root.path("usage");
            int input = usage.path("input_tokens").asInt();
            int output = usage.path("output_tokens").asInt();
            return LlmResponse.builder().requestId(root.path("id").asText(null)).modelCode(model)
                    .createdAt(root.path("created_at").asLong() > 0 ? root.path("created_at").asLong() : null)
                    .status(root.path("status").asText(null)).output(items)
                    .maxOutputTokens(root.path("max_output_tokens").asInt() > 0 ? root.path("max_output_tokens").asInt() : null)
                    .usage(LlmUsage.builder().inputTokens(input).outputTokens(output)
                            .totalTokens(usage.path("total_tokens").asInt(input + output))
                            .inputTokensDetails(usage.path("input_tokens_details").isObject()
                                    ? LlmInputTokensDetails.builder().cachedTokens(usage.path("input_tokens_details").path("cached_tokens").asInt()).build() : null)
                            .outputTokensDetails(usage.path("output_tokens_details").isObject()
                                    ? LlmOutputTokensDetails.builder().reasoningTokens(usage.path("output_tokens_details").path("reasoning_tokens").asInt()).build() : null)
                            .build()).build();
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    private static List<LlmContentBlock> blocks(JsonNode nodes) {
        if (nodes.isMissingNode() || nodes.isNull()) return null;
        if (!nodes.isArray()) throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        List<LlmContentBlock> blocks = new ArrayList<>();
        for (JsonNode node : nodes) {
            String type = node.path("type").asText("");
            JsonNode text = node.path("refusal".equals(type) ? "refusal" : "text");
            if (!List.of("output_text", "reasoning_text", "summary_text", "refusal").contains(type) || !text.isTextual()) {
                throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
            }
            blocks.add("refusal".equals(type)
                    ? LlmContentBlock.builder().type(type).refusal(text.asText()).build()
                    : LlmContentBlock.builder().type(type).text(text.asText()).build());
        }
        return blocks;
    }

    public static LlmResponse call(Request request, String model, OkHttpClient client, RetryParam retry,
                                   BiConsumer<Integer, String> onHttpError) {
        LlmResponse result = AppRetryUtil.retry(() -> {
            try (Response response = client.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) onHttpError.accept(response.code(), body);
                return parseResponse(body, model);
            }
        }, retry);
        if (result == null) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        return result;
    }

    public static void stream(Request request, ExecutorService executor, Supplier<OkHttpClient> client,
                              Supplier<RetryParam> retry, Consumer<String> downstream, BiConsumer<Integer, String> onHttpError) {
        var consumer = ResponsesStreamUtil.cancellable(downstream);
        if (consumer.isCancelled()) return;
        try {
            consumer.setTask(executor.submit(TraceContextSupport.wrap(() -> {
                try {
                    try (Response response = AppRetryUtil.retryForStream(() -> {
                        consumer.checkCancelled();
                        Call call = client.get().newCall(request);
                        consumer.setCall(call);
                        consumer.checkCancelled();
                        Response resp = call.execute();
                        if (!resp.isSuccessful()) {
                            try (Response failed = resp) {
                                String body = failed.body() != null ? failed.body().string() : "";
                                onHttpError.accept(failed.code(), body);
                            }
                        }
                        return resp;
                    }, retry.get(), consumer::isCancelled)) {
                        if (response == null || response.body() == null) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                        consumer.checkCancelled();
                        ResponsesStreamUtil.parse(response.body(), consumer);
                    }
                } catch (Exception e) {
                    if (!consumer.isCancelled()) {
                        log.error("[Responses-stream] 调用失败", e);
                        consumer.accept("[ERROR]");
                    }
                } finally {
                    consumer.release();
                }
            })));
        } catch (RejectedExecutionException e) {
            consumer.accept("[ERROR]");
        }
    }
}

