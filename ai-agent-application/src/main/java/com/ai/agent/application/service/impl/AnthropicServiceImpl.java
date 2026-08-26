package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.AnthropicBO;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.enums.http.AnthropicHttpCodeEnum;
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
 * @Description: Anthropic（Claude）平台 LLM 服务实现。
 *               与其他平台的关键差异：
 *               1. 认证：x-api-key Header（非 Authorization:Bearer），必须携带 anthropic-version
 *               2. Endpoint：/v1/messages（非 /v1/chat/completions）
 *               3. system 消息从 messages 中提取为顶层 system 字段；messages 只含 user/assistant
 *               4. max_tokens 必填
 *               5. 响应：content[0].text；token 字段 input_tokens/output_tokens
 *               6. 流式结束：event: message_stop（无 [DONE]）；文本 chunk 在 content_block_delta 事件
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service.impl
 * @ClassName: AnthropicServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/29
 * @Version: 1.0
 */
@Slf4j
@Service
public class AnthropicServiceImpl implements LlmService {

    private static final String ANTHROPIC_VERSION   = "2023-06-01";
    private static final String EVENT_MESSAGE_STOP    = "message_stop";
    private static final String EVENT_CONTENT_DELTA   = "content_block_delta";
    private static final String EVENT_CONTENT_START    = "content_block_start";
    private static final int    DEFAULT_MAX_TOKENS  = 4096;

    private static final MediaType    JSON        = MediaType.parse("application/json; charset=utf-8");
    
    private static final ObjectMapper MAPPER      = new ObjectMapper();


    private final ExecutorService streamExecutor;
    private final OkHttpConfig okHttpConfig;
    private final RetryConfig retryConfig;
    private final NacosConfig nacosConfig;

    public AnthropicServiceImpl(@Qualifier("anthropicStreamExecutor") ExecutorService streamExecutor,
            OkHttpConfig okHttpConfig,
            RetryConfig retryConfig,
            NacosConfig nacosConfig) {
        this.streamExecutor = streamExecutor;
        this.okHttpConfig = okHttpConfig;
        this.retryConfig = retryConfig;
        this.nacosConfig = nacosConfig;
    }

    // ==================== 同步对话 ====================

    @Override
    public LlmResponse chat(LlmRequest request) {
        fillDefaults(request);
        log.info("[Anthropic-chat] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        String requestBody = buildRequestBody(request, false);
        long start = System.currentTimeMillis();
        LlmResponse result = AppRetryUtil.retry(() -> {
            Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
            try (Response response = okHttpConfig.getClientByPlatform(OkHttpConfigEnum.ANTHROPIC).newCall(okRequest).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        String platformErr = extractErrorMessage(responseBody);
                        log.error("[Anthropic-chat] HTTP {} 失败, platformError={}", response.code(), platformErr);
                        throwByHttpCode(response.code(), platformErr);
                    }
                    if (responseBody.isEmpty()) {
                        log.error("[Anthropic-chat] 响应体为空");
                        throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
                    }
                    return parseResponse(responseBody, request.getModelCode());
            }
        }, retryConfig.getRetryParam(RetryConfigEnum.ANTHROPIC));
        if (result == null) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        log.info("[Anthropic-chat] 调用成功, model={}, inputTokens={}, outputTokens={}, costMs={}",
                                request.getModelCode(), result.getUsage().getInputTokens(), result.getUsage().getOutputTokens(),
                                System.currentTimeMillis() - start);
        return result;
    }

    // ==================== 流式对话 ====================

    @Override
    public void chatStream(LlmRequest request, Consumer<String> chunkConsumer) {
        fillDefaults(request);
        String requestBody = buildRequestBody(request, true);
        log.info("[Anthropic-stream] 开始调用, model={}, endpoint={}", request.getModelCode(), request.getEndpoint());
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        try {
            streamExecutor.submit(() -> {
                if (mdcContext != null) MDC.setContextMap(mdcContext);
                try {
                    Request okRequest = buildOkRequest(request.getEndpoint(), request.getApiKey(), requestBody);
                    Response response = AppRetryUtil.retryForStream(() -> {
                        Response resp = okHttpConfig.getClientByPlatform(OkHttpConfigEnum.ANTHROPIC).newCall(okRequest).execute();
                        if (!resp.isSuccessful()) {
                            String errBody = resp.body() != null ? resp.body().string() : "";
                            String platformMsg = extractErrorMessage(errBody);
                            log.error("[Anthropic-stream] HTTP {} 失败, platformError={}", resp.code(), platformMsg);
                            resp.close();
                            throwByHttpCode(resp.code(), platformMsg);
                        }
                        return resp;
                    }, retryConfig.getRetryParam(RetryConfigEnum.ANTHROPIC));
                    if (response == null || response.body() == null) {
                        log.error("[Anthropic] 连接失败或响应体为空");
                        chunkConsumer.accept("[ERROR]");
                        return;
                    }
                    try {
                        parseStreamResponse(response.body(), request.getModelCode(), chunkConsumer);
                    } finally {
                        response.close();
                    }
                } catch (BizException e) {
                    log.error("[Anthropic-stream] 业务异常", e);
                    chunkConsumer.accept("[ERROR]");
                } catch (Exception e) {
                    log.error("[Anthropic-stream] 未预期异常", e);
                    chunkConsumer.accept("[ERROR]");
                } finally {
                    MDC.clear();
                }
            });
        } catch (RejectedExecutionException e) {
            log.error("[Anthropic-stream] 线程池已满，拒绝请求", e);
            chunkConsumer.accept("[ERROR]");
        }
    }

    /**
     * Anthropic Claude 3 系列具备视觉能力，但其多模态协议使用 content 数组中的 image 块传输图片，
     * 与当前通用多模态接口的 LlmRequest 结构尚未完成适配，暂不支持，返回 null。
     */
    @Override
    public LlmResponse multimodalChat(LlmRequest request) {
        log.warn("[Anthropic] 多模态接口暂未适配：Claude 视觉能力需通过 content image 块传入图片，当前通用接口层尚未完成对接");
        return null;
    }

    @Override
    public LlmModelPage listModels(String apiKey, int pageNo, int pageSize) {
        log.info("[Anthropic-models] 模型列表需单独适配 Anthropic 分页协议，当前返回空列表");
        return LlmModelPage.of(Collections.emptyList(), pageNo, pageSize);
    }

    // ==================== 凭证兜底 ====================

    private void fillDefaults(LlmRequest request) {
        AnthropicBO cfg = null;
        if (StringUtils.isBlank(request.getApiKey())
                || StringUtils.isBlank(request.getEndpoint())
                || StringUtils.isBlank(request.getModelCode())) {
            cfg = nacosConfig.getObject(NacosDataIdEnum.AI_AGENT_ANTHROPIC, "chat", AnthropicBO.class);
        }
        if (StringUtils.isBlank(request.getApiKey()))
            request.setApiKey(cfg != null ? cfg.getApiKey() : null);
        if (StringUtils.isBlank(request.getEndpoint()))
            request.setEndpoint(cfg != null ? cfg.getEndpoint() : null);
        if (StringUtils.isBlank(request.getModelCode()))
            request.setModelCode(cfg != null ? cfg.getModelCode() : null);
        if (StringUtils.isBlank(request.getApiKey())) {
            log.error("[Anthropic] apiKey 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        if (StringUtils.isBlank(request.getEndpoint())) {
            log.error("[Anthropic] endpoint 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
        if (StringUtils.isBlank(request.getModelCode())) {
            log.error("[Anthropic] modelCode 未配置，入参和 Nacos 均为空");
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    // ==================== 请求构建 ====================

    private String buildRequestBody(LlmRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModelCode());
        body.put("stream", stream);
        // Anthropic max_tokens 必填
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : DEFAULT_MAX_TOKENS);

        // 提取 system 消息为顶层字段；messages 只保留 user/assistant（tool 角色映射为 user 携带 tool_result 块）
        String systemPrompt = null;
        List<Object> messages = new ArrayList<>();
        for (LlmMessage msg : request.getMessages()) {
            if ("system".equalsIgnoreCase(msg.getRole())) {
                systemPrompt = msg.isTextOnly() ? msg.getTextContent() : "";
            } else if (msg.getToolCalls() != null) {
                // assistant 携带工具调用：content 数组中可同时包含文本块与 tool_use 块
                Map<String, Object> m = new HashMap<>();
                m.put("role", "assistant");
                m.put("content", buildAssistantToolUseContent(msg));
                messages.add(m);
            } else if (msg.getToolCallId() != null) {
                // Anthropic 无独立 tool 角色，工具结果以 user 消息携带 tool_result 块的形式回传。
                // Anthropic 要求 user/assistant 严格交替，并行工具调用会产生连续多条 role=tool 消息，
                // 必须合并进同一条 user 消息的 content 数组，否则报 "roles must alternate"。
                Map<String, Object> toolResult = Map.of(
                        "type", "tool_result",
                        "tool_use_id", msg.getToolCallId(),
                        "content", msg.getTextContent()
                );
                int lastIndex = messages.size() - 1;
                if (lastIndex >= 0 && isMergeableToolResultMessage(messages.get(lastIndex))) {
                    @SuppressWarnings("unchecked")
                    List<Object> existingContent = (List<Object>) ((Map<String, Object>) messages.get(lastIndex)).get("content");
                    existingContent.add(toolResult);
                } else {
                    Map<String, Object> m = new HashMap<>();
                    m.put("role", "user");
                    List<Object> content = new ArrayList<>();
                    content.add(toolResult);
                    m.put("content", content);
                    messages.add(m);
                }
            } else {
                Map<String, Object> m = new HashMap<>();
                m.put("role", msg.getRole());
                if (msg.isTextOnly()) {
                    m.put("content", msg.getTextContent());
                } else {
                    m.put("content", buildContentArray(msg));
                }
                messages.add(m);
            }
        }
        if (systemPrompt != null) body.put("system", systemPrompt);
        body.put("messages", messages);

        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        // Anthropic 支持 top_k；不支持 frequency_penalty（该平台无此参数）
        if (request.getTopK() != null) body.put("top_k", request.getTopK());
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            body.put("tools", buildAnthropicTools(request.getTools()));
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
     * 判断某条已构造的消息是否可合并新的 tool_result 块：必须是 role=user 且 content 为
     * 以 tool_result 块开头的列表（区别于普通文本 user 消息，普通消息 content 为 String 或图文数组）。
     */
    @SuppressWarnings("unchecked")
    private boolean isMergeableToolResultMessage(Object message) {
        if (!(message instanceof Map)) return false;
        Map<String, Object> m = (Map<String, Object>) message;
        if (!"user".equals(m.get("role"))) return false;
        Object content = m.get("content");
        if (!(content instanceof List) || ((List<?>) content).isEmpty()) return false;
        Object firstBlock = ((List<?>) content).get(0);
        return firstBlock instanceof Map && "tool_result".equals(((Map<?, ?>) firstBlock).get("type"));
    }

    /**
     * 将统一的 OpenAI 风格 tools（{type:function, function:{name,description,parameters}}）
     * 转换为 Anthropic 协议要求的 {name, description, input_schema} 结构。
     */
    private List<Map<String, Object>> buildAnthropicTools(List<Map<String, Object>> openAiTools) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : openAiTools) {
            Object functionObj = tool.get("function");
            if (!(functionObj instanceof Map)) continue;
            Map<?, ?> function = (Map<?, ?>) functionObj;
            Map<String, Object> anthropicTool = new LinkedHashMap<>();
            anthropicTool.put("name", function.get("name"));
            anthropicTool.put("description", function.get("description"));
            anthropicTool.put("input_schema", function.get("parameters"));
            result.add(anthropicTool);
        }
        return result;
    }

    /**
     * 构造 tool_choice 字段：auto → {"type":"auto"}；none → {"type":"none"}；
     * 其余值视为指定工具名 → {"type":"tool","name":toolChoice}。
     */
    private Map<String, Object> buildToolChoice(String toolChoice) {
        if ("auto".equals(toolChoice) || "none".equals(toolChoice)) {
            return Map.of("type", toolChoice);
        }
        return Map.of("type", "tool", "name", toolChoice);
    }

    /**
     * 构造 assistant 携带 tool_use 的 content 数组：正文文本（若有）+ 每个工具调用对应一个 tool_use 块。
     * tool_use.input 需为 JSON 对象，将 LlmToolCall.arguments（JSON 字符串）反序列化回对象。
     */
    private List<Map<String, Object>> buildAssistantToolUseContent(LlmMessage msg) {
        List<Map<String, Object>> parts = new ArrayList<>();
        String text = msg.getTextContent();
        if (StringUtils.isNotBlank(text)) {
            parts.add(Map.of("type", "text", "text", text));
        }
        for (LlmToolCall tc : msg.getToolCalls()) {
            Map<String, Object> input;
            try {
                input = StringUtils.isNotBlank(tc.getArguments())
                        ? MAPPER.readValue(tc.getArguments(), Map.class)
                        : Map.of();
            } catch (IOException e) {
                log.warn("[Anthropic] tool_use.input 反序列化失败，降级为空对象, arguments={}", tc.getArguments());
                input = Map.of();
            }
            Map<String, Object> toolUse = new LinkedHashMap<>();
            toolUse.put("type", "tool_use");
            toolUse.put("id", tc.getId());
            toolUse.put("name", tc.getName());
            toolUse.put("input", input);
            parts.add(toolUse);
        }
        return parts;
    }

    private List<Map<String, Object>> buildContentArray(LlmMessage msg) {
        List<Map<String, Object>> parts = new ArrayList<>();
        for (MessageContent c : msg.getContents()) {
            switch (c.getType()) {
                case TEXT -> parts.add(Map.of("type", "text", "text", c.getValue()));
                case IMAGE -> {
                    String value = c.getValue();
                    Map<String, Object> source = new HashMap<>();
                    if (value.startsWith("data:")) {
                        int semiColon = value.indexOf(';');
                        int comma = value.indexOf(',');
                        source.put("type", "base64");
                        source.put("media_type", value.substring(5, semiColon));
                        source.put("data", comma >= 0 ? value.substring(comma + 1) : "");
                    } else {
                        source.put("type", "url");
                        source.put("url", value);
                    }
                    Map<String, Object> part = new HashMap<>();
                    part.put("type", "image");
                    part.put("source", source);
                    parts.add(part);
                }
                default -> throw new BizException(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
            }
        }
        return parts;
    }

    /** Anthropic 认证：x-api-key + anthropic-version（非 Authorization: Bearer） */
    private Request buildOkRequest(String endpoint, String apiKey, String requestBody) {
        return new Request.Builder()
                .url(endpoint)
                .post(RequestBody.create(requestBody, JSON))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("Content-Type", "application/json")
                .build();
    }

    // ==================== 响应解析 ====================

    /**
     * Anthropic 响应结构：content[0].text，token 字段 input_tokens/output_tokens。
     * 与 OpenAI 差异：
     *   - 无 choices 结构，内容在 root.content[] 中，用单个 LlmChoice 封装保持统一。
     *   - 无 created 字段，用请求发起时间兼底。
     *   - 无 total_tokens，由 input + output 自算。
     */
    private LlmResponse parseResponse(String responseJson, String modelCode) {
        try {
            JsonNode root     = MAPPER.readTree(responseJson);
            String stopReason = root.path("stop_reason").asText("");
            String finish     = "end_turn".equals(stopReason) ? "stop"
                    : "tool_use".equals(stopReason) ? "tool_calls"
                    : stopReason;

            StringBuilder textBuilder = new StringBuilder();
            List<LlmToolCall> toolCalls = null;
            for (JsonNode block : root.path("content")) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    textBuilder.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    if (toolCalls == null) toolCalls = new ArrayList<>();
                    String arguments;
                    try {
                        arguments = MAPPER.writeValueAsString(block.path("input"));
                    } catch (IOException e) {
                        arguments = "{}";
                    }
                    toolCalls.add(LlmToolCall.builder()
                            .id(block.path("id").asText(null))
                            .name(block.path("name").asText(null))
                            .arguments(arguments)
                            .build());
                }
            }

            JsonNode usage    = root.path("usage");
            int input  = usage.path("input_tokens").asInt(0);
            int output = usage.path("output_tokens").asInt(0);
            return LlmResponse.builder()
                    .requestId(root.path("id").asText(null))
                    .modelCode(modelCode)
                    .createdAt(System.currentTimeMillis() / 1000)
                    .choices(List.of(LlmChoice.builder()
                            .content(textBuilder.toString())
                            .finishReason(finish)
                            .toolCalls(toolCalls)
                            .build()))
                    .usage(LlmUsage.builder()
                            .inputTokens(input)
                            .outputTokens(output)
                            .totalTokens(input + output)
                            .build())
                    .build();
        } catch (IOException e) {
            log.error("[Anthropic-chat] 响应解析失败", e);
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    /**
     * Anthropic 流式格式：每行可能是 event: xxx 或 data: {...}。
     * 文本 chunk：event: content_block_delta + data.delta.type=text_delta
     * 工具调用：
     *   event: content_block_start + data.content_block.type=tool_use → 获取 id/name
     *   event: content_block_delta + data.delta.type=input_json_delta → arguments 碎片
     * 流结束：event: message_stop（无 [DONE]）
     */
    private void parseStreamResponse(ResponseBody responseBody, String modelCode, Consumer<String> chunkConsumer) {
        // key=block_index, value=[id, name, arguments累积]
        Map<Integer, String[]> toolCallsMap = new LinkedHashMap<>();
        int currentBlockIndex = -1;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
            String line;
            String currentEvent = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                    if (EVENT_MESSAGE_STOP.equals(currentEvent)) {
                        flushToolCalls(toolCallsMap, modelCode, chunkConsumer);
                        chunkConsumer.accept(null);
                        return;
                    }
                } else if (line.startsWith("data: ")) {
                    try {
                        JsonNode dataNode = MAPPER.readTree(line.substring(6));
                        if (EVENT_CONTENT_START.equals(currentEvent)) {
                            // 检查是否是 tool_use block
                            JsonNode block = dataNode.path("content_block");
                            if ("tool_use".equals(block.path("type").asText(""))) {
                                currentBlockIndex = dataNode.path("index").asInt(-1);
                                if (currentBlockIndex >= 0) {
                                    String[] entry = toolCallsMap.computeIfAbsent(currentBlockIndex, k -> new String[]{"", "", ""});
                                    entry[0] = block.path("id").asText("");
                                    entry[1] = block.path("name").asText("");
                                }
                            } else {
                                currentBlockIndex = -1;
                            }
                        } else if (EVENT_CONTENT_DELTA.equals(currentEvent)) {
                            JsonNode delta = dataNode.path("delta");
                            String deltaType = delta.path("type").asText("");
                            if ("text_delta".equals(deltaType)) {
                                // 文本 chunk 实时推
                                String chunk = delta.path("text").asText("");
                                if (!chunk.isEmpty()) chunkConsumer.accept(chunk);
                            } else if ("input_json_delta".equals(deltaType)) {
                                // tool_use arguments 碎片聚合
                                int blockIndex = dataNode.path("index").asInt(currentBlockIndex);
                                if (blockIndex >= 0 && toolCallsMap.containsKey(blockIndex)) {
                                    toolCallsMap.get(blockIndex)[2] += delta.path("partial_json").asText("");
                                }
                            }
                        }
                    } catch (IOException e) {
                        log.warn("[Anthropic-stream] chunk 解析失败，跳过");
                    }
                }
            }
            // 读完未收到 message_stop，主动结束
            flushToolCalls(toolCallsMap, modelCode, chunkConsumer);
            chunkConsumer.accept(null);
        } catch (IOException e) {
            log.error("[Anthropic-stream] 流式响应解析失败, model={}", modelCode, e);
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
            log.warn("[Anthropic-stream] tool_calls 序列化失败，跳过, model={}", modelCode, e);
        }
    }

    // ==================== 工具方法 ====================

    private void throwByHttpCode(int httpCode, String platformMsg) {
        // Anthropic: 401=认证失败, 400=参数非法, 429=限速, 529=过载(同限速)
        ErrorCodeEnum errorCode;
        if (httpCode == AnthropicHttpCodeEnum.UNAUTHORIZED.getCode()) {
            errorCode = ErrorCodeEnum.LLM_AUTH_FAILED;
        } else if (httpCode == AnthropicHttpCodeEnum.BAD_REQUEST.getCode()
                || httpCode == AnthropicHttpCodeEnum.UNPROCESSABLE.getCode()) {
            errorCode = ErrorCodeEnum.PARAM_ILLEGAL;
        } else if (httpCode == AnthropicHttpCodeEnum.RATE_LIMIT.getCode()
                || httpCode == AnthropicHttpCodeEnum.OVERLOADED.getCode()) {
            errorCode = ErrorCodeEnum.LLM_RATE_LIMIT;
        } else {
            errorCode = ErrorCodeEnum.LLM_CALL_FAILED;
        }
        throw new BizException(errorCode, platformMsg);
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            // Anthropic 错误结构：{"type":"error","error":{"type":"...","message":"..."}}
            JsonNode errorNode = root.path("error");
            String msg = errorNode.path("message").asText("");
            if (msg.isEmpty()) return truncate(responseBody);
            String type = errorNode.path("type").asText("");
            return type.isEmpty() ? msg : "[" + type + "] " + msg;
        } catch (Exception e) {
            return truncate(responseBody);
        }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }


}

