package com.ai.agent.application.utils;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

public final class GeminiInteractionsUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> RESERVED = Set.of("model", "input", "stream", "system_instruction",
            "generation_config", "tools", "previous_interaction_id", "continuation_token", "continuationToken", "store", "background", "state",
            "apiKey", "endpoint", "instructions", "temperature", "topP", "top_p", "maxOutputTokens",
            "max_output_tokens", "generationConfig", "previousInteractionId", "extraParams",
            "stop_sequences", "seed", "thinking_level", "thinking_summaries", "tool_choice",
            "messages", "interactionSteps", "nativeMessages", "modelCode", "maxTokens");
    private static final Set<String> CONTENT_TYPES = Set.of("text", "image", "audio", "video", "document");
    private static final Set<String> STEP_TYPES = Set.of("user_input", "model_output", "thought", "function_call", "function_result");

    private GeminiInteractionsUtil() {}

    public static String buildRequestBody(LlmRequest request, boolean stream) {
        if (request == null) throw illegal();
        try {
            JsonNode input = buildInput(request);
            if (input == null || input.isNull()) {
                if (request.getContinuationToken() == null || request.getContinuationToken().isBlank()
                        || request.getPreviousInteractionId() == null || request.getPreviousInteractionId().isBlank()
                        || Boolean.FALSE.equals(request.getStore())) throw illegal();
            } else {
                validateInput(input);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            if (request.getExtraParams() != null) {
                request.getExtraParams().forEach((key, value) -> {
                    if (key == null || RESERVED.contains(key)
                            || (request.getGenerationConfig() != null && request.getGenerationConfig().containsKey(key))) throw illegal();
                    body.put(key, value);
                });
            }
            body.put("model", request.getModel());
            if (input != null && !input.isNull()) body.put("input", input);
            body.put("stream", stream);
            if (request.getInstructions() != null) body.put("system_instruction", request.getInstructions());
            if (request.getTools() != null) body.put("tools", request.getTools());
            Map<String, Object> config = new LinkedHashMap<>();
            if (request.getGenerationConfig() != null) config.putAll(request.getGenerationConfig());
            if (request.getTemperature() != null) config.put("temperature", request.getTemperature());
            if (request.getTopP() != null) config.put("top_p", request.getTopP());
            if (request.getMaxOutputTokens() != null) config.put("max_output_tokens", request.getMaxOutputTokens());
            if (!config.isEmpty()) body.put("generation_config", config);
            if (request.getPreviousInteractionId() != null) body.put("previous_interaction_id", request.getPreviousInteractionId());
            if (request.getContinuationToken() != null) body.put("continuation_token", request.getContinuationToken());
            if (request.getStore() != null) body.put("store", request.getStore());
            if (request.getBackground() != null) body.put("background", request.getBackground());
            return MAPPER.writeValueAsString(body);
        } catch (IllegalArgumentException | IOException e) {
            throw illegal();
        }
    }

    private static JsonNode buildInput(LlmRequest request) {
        if (request.getMessages() != null && request.getInteractionSteps() != null) throw illegal();
        if (request.getInteractionSteps() != null) {
            if (request.getInteractionSteps().isEmpty()) throw illegal();
            List<Map<String, Object>> steps = new ArrayList<>();
            for (LlmInteractionStep step : request.getInteractionSteps()) {
                if (step == null || step.getType() == null || !STEP_TYPES.contains(step.getType())) throw illegal();
                Map<String, Object> wire = new LinkedHashMap<>();
                wire.put("type", step.getType());
                if (step.getId() != null) wire.put("id", step.getId());
                if (step.getCallId() != null) wire.put("call_id", step.getCallId());
                if (step.getName() != null) wire.put("name", step.getName());
                if (step.getArguments() != null) wire.put("arguments", step.getArguments());
                if (step.getContent() != null) wire.put("content", buildContents(step.getContent()));
                if (step.getResult() != null && step.getResultContents() != null) throw illegal();
                if (step.getResult() != null) wire.put("result", step.getResult());
                if (step.getResultContents() != null) wire.put("result", buildContents(step.getResultContents()));
                if (step.getSignature() != null) wire.put("signature", step.getSignature());
                if (step.getSummary() != null) wire.put("summary", buildContents(step.getSummary()));
                Set<String> allowed = switch (step.getType()) {
                    case "user_input", "model_output" -> Set.of("type", "id", "content");
                    case "function_call" -> Set.of("type", "id", "name", "arguments");
                    case "function_result" -> Set.of("type", "id", "call_id", "name", "result");
                    default -> Set.of("type", "id", "content", "signature", "summary");
                };
                if (!allowed.containsAll(wire.keySet())) throw illegal();
                steps.add(wire);
            }
            return MAPPER.valueToTree(steps);
        }
        if (request.getMessages() == null) return null;
        if (request.getMessages().isEmpty()) throw illegal();
        List<Map<String, Object>> steps = new ArrayList<>();
        for (LlmMessage message : request.getMessages()) {
            if (message == null || !("user".equals(message.getRole()) || "assistant".equals(message.getRole()))
                    || message.getName() != null || message.getAudio() != null || message.getToolCalls() != null
                    || message.getToolCallId() != null || message.getReasoningContent() != null
                    || message.getThinkingBlocks() != null || message.getContents() == null) throw illegal();
            steps.add(Map.of("type", "user".equals(message.getRole()) ? "user_input" : "model_output",
                    "content", buildContents(message.getContents())));
        }
        return MAPPER.valueToTree(steps);
    }

    private static List<Map<String, Object>> buildContents(List<MessageContent> contents) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MessageContent content : contents) {
            if (content == null || content.getType() == null) throw illegal();
            JsonNode fields = MAPPER.valueToTree(content);
            fields.fields().forEachRemaining(field -> {
                if (!field.getValue().isNull() && !Set.of("type", "value", "text", "uri", "data", "mimeType")
                        .contains(field.getKey())) throw illegal();
            });
            String type = content.getType().getWireType();
            if ("file".equals(type)) type = "document";
            if (!CONTENT_TYPES.contains(type)) throw illegal();
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("type", type);
            if (content.getText() != null) wire.put("text", content.getText());
            if (content.getUri() != null) wire.put("uri", content.getUri());
            if (content.getData() != null) wire.put("data", content.getData());
            if (content.getMimeType() != null) wire.put("mime_type", content.getMimeType());
            if (content.getValue() != null) {
                if (content.getText() != null || content.getUri() != null || content.getData() != null) throw illegal();
                String value = content.getValue();
                if ("text".equals(type)) wire.put("text", value);
                else if (value.startsWith("data:")) {
                    int separator = value.indexOf(";base64,");
                    if (separator < 5) throw illegal();
                    String mime = value.substring(5, separator);
                    if (content.getMimeType() != null && !mime.equals(content.getMimeType())) throw illegal();
                    wire.put("mime_type", mime);
                    wire.put("data", value.substring(separator + 8));
                } else wire.put("uri", value);
            }
            Set<String> allowed = "text".equals(type) ? Set.of("type", "text") : Set.of("type", "uri", "data", "mime_type");
            if (!allowed.containsAll(wire.keySet())) throw illegal();
            validateContent(MAPPER.valueToTree(wire));
            result.add(wire);
        }
        return result;
    }

    private static void validateInput(JsonNode input) {
        if (input == null || !input.isArray() || input.isEmpty()) throw illegal();
        input.forEach(GeminiInteractionsUtil::validateStep);
    }

    private static void validateStep(JsonNode step) {
        String type = step.path("type").asText();
        if (!step.isObject() || !STEP_TYPES.contains(type)) throw illegal();
        if ("user_input".equals(type) || "model_output".equals(type)) {
            JsonNode content = step.path("content");
            if (!content.isArray() || content.isEmpty()) throw illegal();
            content.forEach(GeminiInteractionsUtil::validateContent);
        } else if ("function_call".equals(type)) {
            if (!nonBlankText(step.path("name")) || !nonBlankText(step.path("id"))
                    || !step.path("arguments").isObject()) throw illegal();
        } else if ("function_result".equals(type)) {
            JsonNode result = step.path("result");
            if (!nonBlankText(step.path("call_id")) || !(result.isTextual() || result.isArray())) throw illegal();
            if (result.isArray()) {
                if (result.isEmpty()) throw illegal();
                result.forEach(GeminiInteractionsUtil::validateContent);
            }
        }
    }

    private static void validateContent(JsonNode content) {
        String type = content.path("type").asText();
        if (!content.isObject() || !CONTENT_TYPES.contains(type)) throw illegal();
        if ("text".equals(type)) {
            if (!nonBlankText(content.path("text"))) throw illegal();
            return;
        }
        boolean uri = nonBlankText(content.path("uri"));
        boolean data = nonBlankText(content.path("data"));
        if (uri == data) throw illegal();
        if ((content.has("uri") && !uri) || (content.has("data") && !data)) throw illegal();
        JsonNode mime = content.path("mime_type");
        if (data) {
            if (!nonBlankText(mime)) throw illegal();
            try {
                if (Base64.getDecoder().decode(content.path("data").asText()).length == 0) throw illegal();
            } catch (IllegalArgumentException e) {
                throw illegal();
            }
        }
        if (content.has("mime_type") && !nonBlankText(mime)) throw illegal();
        if ("document".equals(type) && nonBlankText(mime)
                && !Set.of("application/pdf", "text/csv").contains(mime.asText())) throw illegal();
    }

    private static boolean nonBlankText(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank();
    }

    private static BizException illegal() {
        return new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
    }

    public static LlmInteractionsResponse parseResponse(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isObject() && root.has("error")) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
            if (root == null || !root.isObject() || !nonBlankText(root.path("status"))) {
                throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
            }
            checkStatus(root.path("status").asText());
            if (Set.of("completed", "incomplete", "requires_action", "requires_continuation")
                    .contains(root.path("status").asText()) && !root.path("steps").isArray()) {
                throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
            }
            List<Map<String, Object>> steps = root.hasNonNull("steps")
                    ? MAPPER.convertValue(root.get("steps"), new TypeReference<List<Map<String, Object>>>() {}) : null;
            StringBuilder text = new StringBuilder();
            for (JsonNode step : root.path("steps")) {
                if ("model_output".equals(step.path("type").asText())) {
                    for (JsonNode content : step.path("content")) {
                        if ("text".equals(content.path("type").asText()) && content.path("text").isTextual()) {
                            text.append(content.path("text").asText());
                        }
                    }
                }
            }
            JsonNode usage = root.path("usage");
            return LlmInteractionsResponse.builder().id(root.path("id").asText(null))
                    .model(root.path("model").asText(null)).status(root.path("status").asText())
                    .continuationToken(root.path("continuation_token").asText(null))
                    .created(root.path("created").asText(null)).updated(root.path("updated").asText(null))
                    .steps(steps).outputText(text.toString())
                    .usage(usage.isObject() ? LlmUsage.builder()
                            .inputTokens(integer(usage, "total_input_tokens"))
                            .outputTokens(integer(usage, "total_output_tokens"))
                            .totalTokens(integer(usage, "total_tokens"))
                            .inputTokensDetails(usage.has("total_cached_tokens") ? LlmInputTokensDetails.builder()
                                    .cachedTokens(integer(usage, "total_cached_tokens")).build() : null)
                            .outputTokensDetails(usage.has("total_thought_tokens") ? LlmOutputTokensDetails.builder()
                                    .reasoningTokens(integer(usage, "total_thought_tokens")).build() : null).build() : null)
                    .build();
        } catch (IOException | IllegalArgumentException e) {
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    private static Integer integer(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.path(key).asInt() : null;
    }

    private static void checkStatus(String status) {
        if ("failed".equals(status) || "cancelled".equals(status)) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
    }

    public static void parseStream(ResponseBody body, Consumer<String> downstream) {
        var consumer = ResponsesStreamUtil.cancellable(downstream);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            boolean hasData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                consumer.checkCancelled();
                if (line.isEmpty()) {
                    if (hasData && dispatch(event, data.toString(), consumer)) return;
                    event = null;
                    data.setLength(0);
                    hasData = false;
                } else if (!line.startsWith(":")) {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if ("event".equals(field)) event = value;
                    else if ("data".equals(field)) {
                        if (hasData) data.append('\n');
                        data.append(value);
                        hasData = true;
                    }
                }
            }
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
    }

    private static boolean dispatch(String event, String data, Consumer<String> consumer) throws IOException {
        // A completed event ends the stream immediately; a standalone legacy marker is never a terminal.
        if ("[DONE]".equals(data.trim())) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        JsonNode root = MAPPER.readTree(data);
        if (root == null || !root.isObject()) throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        String type = root.path("event_type").asText("");
        if (type.isBlank() && event != null && !event.isBlank()) {
            type = event;
            ((ObjectNode) root).put("event_type", type);
            data = MAPPER.writeValueAsString(root);
        }
        consumer.accept(data);
        if ("error".equals(type)) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        checkStatus(root.path("interaction").path("status").asText(root.path("status").asText("")));
        if ("interaction.completed".equals(type)) {
            if (!nonBlankText(root.path("interaction").path("status")) && !nonBlankText(root.path("status"))) {
                throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
            }
            consumer.accept(null);
            return true;
        }
        return false;
    }
}

