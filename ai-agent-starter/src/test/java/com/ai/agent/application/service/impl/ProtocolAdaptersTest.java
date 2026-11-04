package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.utils.ResponsesApiUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProtocolAdaptersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DeepseekServiceImpl deepseek = new DeepseekServiceImpl(null, null, null, null);

    static Stream<LlmService> responsesAdapters() {
        return Stream.of(new DeepseekServiceImpl(null, null, null, null),
                new OpenAIServiceImpl(null, null, null, null), new DoubaoServiceImpl(null, null, null, null),
                new QwenServiceImpl(null, null, null, null), new GlmServiceImpl(null, null, null, null),
                new QianfanServiceImpl(null, null, null, null), new MoonshotServiceImpl(null, null, null, null),
                new MiniMaxServiceImpl(null, null, null, null), new MimoServiceImpl(null, null, null, null),
                new TokenHubServiceImpl(null, null, null, null), new OllamaServiceImpl(null, null, null, null));
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void legacyChatCoreFieldsWinAndExtraToolsFallbackSurvives(LlmService adapter) throws IOException {
        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", Map.of("name", "lookup")));
        LlmRequest request = LlmRequest.builder().modelCode("model")
                .messages(List.of(LlmMessage.ofText("user", "hello")))
                .extraParams(Map.of("model", "evil", "messages", "evil", "input", "evil",
                        "stream", true, "tools", tools, "tool_choice", "auto")).build();
        JsonNode original = MAPPER.valueToTree(request);
        for (boolean stream : List.of(false, true)) {
            String json = ReflectionTestUtils.invokeMethod(adapter, "buildRequestBody", request, stream);
            JsonNode body = MAPPER.readTree(json);
            assertEquals("model", body.path("model").asText());
            assertEquals("hello", body.path("messages").path(0).path("content").asText());
            assertEquals(stream, body.path("stream").asBoolean());
            assertFalse(body.has("input"));
            assertEquals(MAPPER.valueToTree(tools), body.path("tools"));
            assertEquals("auto", body.path("tool_choice").asText());
            assertEquals(original, MAPPER.valueToTree(request));
        }
    }

    @Test
    void deepseekEffortWithoutThinkingTypeIsTopLevel() throws IOException {
        LlmRequest request = LlmRequest.builder().modelCode("model")
                .messages(List.of(LlmMessage.ofText("user", "hello")))
                .extraParams(Map.of("reasoning_effort", "high")).build();
        JsonNode body = chatBody(request);
        assertEquals("high", body.path("reasoning_effort").asText());
        assertFalse(body.has("thinking"));
        assertFalse(body.has("thinking_type"));
    }

    @Test
    void deepseekThinkingDoesNotNestEffortAndToolRoundKeepsReasoning() throws IOException {
        LlmToolCall call = LlmToolCall.builder().id("call-1").name("lookup").arguments("{}").build();
        LlmMessage assistant = LlmMessage.ofToolCalls(List.of(call), "answer");
        assistant.setReasoningContent("signed reasoning history");
        LlmRequest request = LlmRequest.builder().modelCode("model")
                .messages(List.of(assistant, LlmMessage.ofToolResult("call-1", "result")))
                .extraParams(Map.of("thinking_type", "enabled", "reasoning_effort", "high")).build();
        JsonNode body = chatBody(request);
        assertEquals(MAPPER.valueToTree(Map.of("type", "enabled")), body.path("thinking"));
        assertEquals("high", body.path("reasoning_effort").asText());
        assertFalse(body.has("thinking_type"));
        JsonNode message = body.path("messages").path(0);
        assertEquals("assistant", message.path("role").asText());
        assertEquals("signed reasoning history", message.path("reasoning_content").asText());
        assertEquals("answer", message.path("content").asText());
        assertEquals("call-1", message.path("tool_calls").path(0).path("id").asText());
        assertEquals("lookup", message.path("tool_calls").path(0).path("function").path("name").asText());
        assertEquals("{}", message.path("tool_calls").path(0).path("function").path("arguments").asText());
        assertEquals("call-1", body.path("messages").path(1).path("tool_call_id").asText());
        assertEquals("result", body.path("messages").path(1).path("content").asText());
    }

    @Test
    void deepseekResponsesMapsEffortAndFiltersInternalKeys() throws IOException {
        LlmResponsesRequest request = responsesRequest();
        request.setExtraParams(Map.of("reasoning_effort", "high", "thinking_type", "enabled",
                "skip_temperature", true, "stream", true, "previous_response_id", "previous"));
        JsonNode body = responsesBody(deepseek, request, false);
        assertEquals(MAPPER.valueToTree(Map.of("effort", "high")), body.path("reasoning"));
        assertEquals("previous", body.path("previous_response_id").asText());
        assertFalse(body.path("stream").asBoolean());
        assertNoDeepseekInternalKeys(body);
    }

    @Test
    void deepseekResponsesExtraToolsPassThroughWhenCommonToolsAreAbsent() throws IOException {
        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "name", "extra_lookup"));
        Map<String, Object> toolChoice = Map.of("type", "function", "name", "extra_lookup");
        Map<String, Object> reasoning = Map.of("summary", "detailed");
        for (boolean emptyTools : List.of(false, true)) {
            LlmResponsesRequest request = responsesRequest();
            request.setTools(emptyTools ? List.of() : null);
            request.setToolChoice(null);
            for (boolean stream : List.of(false, true)) {
                Map<String, Object> extra = Map.of("tools", tools, "tool_choice", toolChoice,
                        "reasoning", reasoning, "reasoning_effort", "high", "thinking_type", "enabled",
                        "skip_temperature", true, "stream", !stream);
                Map<String, Object> original = new LinkedHashMap<>(extra);
                request.setExtraParams(extra);
                JsonNode body = responsesBody(deepseek, request, stream);
                assertEquals(MAPPER.valueToTree(tools), body.path("tools"));
                assertEquals(MAPPER.valueToTree(toolChoice), body.path("tool_choice"));
                assertEquals(MAPPER.valueToTree(Map.of("summary", "detailed", "effort", "high")),
                        body.path("reasoning"));
                assertEquals(stream, body.path("stream").asBoolean());
                assertNoDeepseekInternalKeys(body);
                assertSame(extra, request.getExtraParams());
                assertEquals(original, extra);
                assertEquals(Map.of("summary", "detailed"), reasoning);
            }
        }
    }

    @Test
    void deepseekResponsesOriginalReasoningObjectWinsWithoutLosingNestedKeys() throws IOException {
        Map<String, Object> reasoning = Map.of("effort", "low", "summary", "detailed",
                "vendor", Map.of("nested", List.of(1, 2), "enabled", true));
        LlmResponsesRequest request = responsesRequest();
        request.setExtraParams(Map.of("reasoning", reasoning, "reasoning_effort", "high",
                "thinking_type", "enabled", "skip_temperature", true, "stream", false));
        JsonNode body = responsesBody(deepseek, request, true);
        assertEquals(MAPPER.valueToTree(reasoning), body.path("reasoning"));
        assertTrue(body.path("stream").asBoolean());
        assertNoDeepseekInternalKeys(body);
        assertEquals(reasoning, request.getExtraParams().get("reasoning"));
    }

    @Test
    void deepseekResponsesMergesEffortIntoExistingReasoningWithoutMutatingHistory() throws IOException {
        Map<String, Object> reasoning = Map.of("summary", "detailed");
        LlmResponsesRequest request = responsesRequest();
        request.setExtraParams(Map.of("reasoning", reasoning, "reasoning_effort", "high"));
        assertEquals(MAPPER.valueToTree(Map.of("summary", "detailed", "effort", "high")),
                responsesBody(deepseek, request, false).path("reasoning"));
        assertEquals(Map.of("summary", "detailed"), reasoning);
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void responsesCommonFieldsWinOverExtrasAndStreamIsForced(LlmService adapter) throws IOException {
        ResponsesTransportTest.Provider provider = provider(adapter);
        LlmResponsesRequest request = responsesRequest(adapter);
        Map<String, Object> extra = new LinkedHashMap<>();
        for (String key : List.of("model", "input", "instructions", "temperature", "top_p",
                "max_output_tokens", "tools", "tool_choice")) {
            extra.put(key, "must-not-overwrite");
        }
        if (!provider.stateless()) extra.put("previous_response_id", "previous");
        extra.put("store", false);
        extra.put("include", List.of("reasoning.encrypted_content"));
        for (boolean stream : List.of(false, true)) {
            extra.put("stream", !stream);
            request.setExtraParams(extra);
            Map<String, Object> original = new LinkedHashMap<>(extra);
            JsonNode body = responsesBody(adapter, request, stream);
            assertEquals("model", body.path("model").asText());
            assertEquals(MAPPER.valueToTree(request.getInput()), body.path("input"));
            assertEquals("instructions", body.path("instructions").asText());
            assertEquals(0.5, body.path("temperature").asDouble());
            assertEquals(0.8, body.path("top_p").asDouble());
            assertEquals(123, body.path("max_output_tokens").asInt());
            assertEquals(MAPPER.valueToTree(request.getTools()), body.path("tools"));
            assertEquals(provider.namedChoice() ? MAPPER.valueToTree(ResponsesTransportTest.expectedNamedChoice(provider))
                    : MAPPER.valueToTree("auto"), body.path("tool_choice"));
            if (provider.stateless()) assertFalse(body.has("previous_response_id"));
            else assertEquals("previous", body.path("previous_response_id").asText());
            assertEquals(MAPPER.valueToTree(List.of("reasoning.encrypted_content")), body.path("include"));
            assertFalse(body.path("store").asBoolean());
            assertTrue(body.has("stream"));
            assertEquals(stream, body.path("stream").asBoolean());
            assertEquals(!stream, extra.get("stream"));
            assertSame(extra, request.getExtraParams());
            assertEquals(original, extra);
            assertFalse(body.has("apiKey"));
            assertFalse(body.has("endpoint"));
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void responsesBuiltInToolChoicesStayStrings(LlmService adapter) throws IOException {
        ResponsesTransportTest.Provider provider = provider(adapter);
        for (String choice : List.of("auto", "none", "required")) {
            for (boolean stream : List.of(false, true)) {
                LlmResponsesRequest request = responsesRequest(adapter);
                request.setToolChoice(choice);
                boolean supported = "auto".equals(choice) || provider.namedChoice()
                        || provider == ResponsesTransportTest.Provider.Glm && "none".equals(choice);
                if (supported) assertEquals(choice, responsesBody(adapter, request, stream).path("tool_choice").asText());
                else assertEquals(ErrorCodeEnum.PARAM_ILLEGAL,
                        assertThrows(BizException.class, () -> responsesBody(adapter, request, stream)).getErrorCode());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void responsesNamedFunctionChoicesFollowProviderCapabilities(LlmService adapter) throws IOException {
        Map<String, Object> named = Map.of("type", "function", "name", "lookup");
        for (Object choice : List.of("lookup", named)) {
            for (boolean extra : List.of(false, true)) {
                if (!extra && !(choice instanceof String)) continue;
                if (extra && choice instanceof String && (adapter instanceof OpenAIServiceImpl
                        || adapter instanceof DeepseekServiceImpl || adapter instanceof DoubaoServiceImpl)) continue;
                for (boolean stream : List.of(false, true)) {
                    LlmResponsesRequest request = responsesRequest(adapter);
                    request.setToolChoice(extra ? null : (String) choice);
                    if (extra) request.setExtraParams(Map.of("tool_choice", choice));
                    if (provider(adapter).namedChoice()) {
                        assertEquals(MAPPER.valueToTree(ResponsesTransportTest.expectedNamedChoice(provider(adapter))),
                                responsesBody(adapter, request, stream).path("tool_choice"));
                    } else {
                        assertEquals(ErrorCodeEnum.PARAM_ILLEGAL,
                                assertThrows(BizException.class, () -> responsesBody(adapter, request, stream)).getErrorCode());
                    }
                }
            }
        }
    }

    @Test
    void qwenNamedChoiceSelectsOneAllowedToolFromMultipleToolsWithoutMutatingRequest() throws IOException {
        LlmService adapter = new QwenServiceImpl(null, null, null, null);
        for (boolean extraChoice : List.of(false, true)) {
            for (boolean extraTools : List.of(false, true)) {
                LlmResponsesRequest request = responsesRequest(adapter);
                List<Map<String, Object>> tools = List.of(request.getTools().get(0),
                        Map.of("type", "function", "name", "second_lookup", "parameters", Map.of("type", "object")));
                Map<String, Object> extra = new LinkedHashMap<>();
                request.setToolChoice(extraChoice ? null : "lookup");
                extra.put("tool_choice", extraChoice ? Map.of("type", "function", "name", "lookup") : "required");
                request.setTools(extraTools ? null : tools);
                if (extraTools) extra.put("tools", tools);
                request.setExtraParams(extra);
                JsonNode original = MAPPER.valueToTree(request);
                for (boolean stream : List.of(false, true)) {
                    JsonNode body = responsesBody(adapter, request, stream);
                    assertEquals(MAPPER.valueToTree(tools), body.path("tools"));
                    assertEquals(MAPPER.valueToTree(ResponsesTransportTest.expectedNamedChoice(ResponsesTransportTest.Provider.Qwen)),
                            body.path("tool_choice"));
                    assertSame(extra, request.getExtraParams());
                    assertEquals(original, MAPPER.valueToTree(request));
                }
            }
        }
    }

    @Test
    void qwenLiteralChoicesKeepStringsAndPublicChoiceWinsOverExtraChoice() throws IOException {
        LlmService adapter = new QwenServiceImpl(null, null, null, null);
        for (String choice : List.of("auto", "none", "required")) {
            for (boolean extraChoice : List.of(false, true)) {
                LlmResponsesRequest request = responsesRequest(adapter);
                request.setToolChoice(extraChoice ? null : choice);
                Map<String, Object> extra = Map.of("tool_choice", extraChoice ? choice
                        : Map.of("type", "function", "name", "second_lookup"));
                request.setExtraParams(extra);
                JsonNode original = MAPPER.valueToTree(request);
                for (boolean stream : List.of(false, true)) {
                    assertEquals(choice, responsesBody(adapter, request, stream).path("tool_choice").asText());
                    assertSame(extra, request.getExtraParams());
                    assertEquals(original, MAPPER.valueToTree(request));
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void responsesExtraFunctionToolsAreUsedWithoutMutatingNestedParameters(LlmService adapter) throws IOException {
        Map<String, Object> parameters = Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")));
        List<Map<String, Object>> tools = List.of(Map.of("type", "function", "name", "lookup", "parameters", parameters));
        Map<String, Object> extra = Map.of("tools", tools, "tool_choice", "auto");
        for (boolean empty : List.of(false, true)) {
            for (boolean stream : List.of(false, true)) {
                LlmResponsesRequest request = responsesRequest(adapter);
                request.setTools(empty ? List.of() : null);
                request.setToolChoice(null);
                request.setExtraParams(extra);
                JsonNode original = MAPPER.valueToTree(request);
                JsonNode body = responsesBody(adapter, request, stream);
                assertEquals(MAPPER.valueToTree(tools), body.path("tools"));
                assertEquals("auto", body.path("tool_choice").asText());
                assertEquals(stream, body.path("stream").asBoolean());
                assertSame(extra, request.getExtraParams());
                assertEquals(original, MAPPER.valueToTree(request));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void responsesReasoningAndFunctionRoundTripInputStaysInOrder(LlmService adapter) throws IOException {
        LlmResponsesRequest request = responsesRequest(adapter);
        List<LlmResponsesInput> input = List.of(request.getInput().get(0),
                LlmResponsesInput.builder().type("function_call").callId("call-1").name("lookup").arguments("{}").build(),
                LlmResponsesInput.builder().type("function_call_output").callId("call-1").output("result").build());
        request.setInput(input);
        JsonNode original = MAPPER.valueToTree(request);
        for (boolean stream : List.of(false, true)) {
            JsonNode body = responsesBody(adapter, request, stream);
            assertEquals(MAPPER.valueToTree(input), body.path("input"));
            assertEquals("reasoning", body.path("input").path(0).path("type").asText());
            assertEquals("call-1", body.path("input").path(1).path("call_id").asText());
            assertEquals("result", body.path("input").path(2).path("output").asText());
            assertSame(input, request.getInput());
            assertEquals(original, MAPPER.valueToTree(request));
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void responsesNullExtrasAndOptionalFieldsUseDefaultStreamWithoutMutatingRequest(LlmService adapter) throws IOException {
        LlmResponsesRequest request = responsesRequest(adapter);
        request.setExtraParams(null);
        request.setInstructions(null);
        request.setTemperature(null);
        request.setTopP(null);
        request.setMaxOutputTokens(null);
        request.setTools(null);
        request.setToolChoice(null);
        JsonNode original = MAPPER.valueToTree(request);
        for (boolean stream : List.of(false, true)) {
            JsonNode body = responsesBody(adapter, request, stream);
            assertEquals(stream, body.path("stream").asBoolean());
            assertEquals("model", body.path("model").asText());
            assertEquals(MAPPER.valueToTree(request.getInput()), body.path("input"));
            for (String key : List.of("instructions", "temperature", "top_p", "max_output_tokens", "tools")) {
                assertFalse(body.has(key), key);
            }
            assertTrue(!body.has("tool_choice") || "auto".equals(body.path("tool_choice").asText()));
            assertEquals(original, MAPPER.valueToTree(request));
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void responsesParserKeepsEncryptedReasoningContentAndSummary(LlmService adapter) throws IOException {
        String json = """
                {"id":"response-1","created_at":123,"status":"completed","max_output_tokens":100,
                 "output":[
                   {"id":"reason-1","type":"reasoning","status":"completed","encrypted_content":"opaque",
                    "summary":[{"type":"summary_text","text":"summary"}],
                    "content":[{"type":"reasoning_text","text":"first"},{"type":"reasoning_text","text":"second"}]},
                   {"id":"message-1","type":"message","status":"completed","role":"assistant",
                    "content":[{"type":"output_text","text":"answer"}]},
                   {"id":"tool-1","type":"function_call","status":"completed","call_id":"call-1",
                    "name":"lookup","arguments":"{}"}],
                 "usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5,
                          "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":1}}}
                """;
        LlmResponse response = adapter instanceof OpenAIServiceImpl || adapter instanceof DeepseekServiceImpl || adapter instanceof DoubaoServiceImpl
                ? ReflectionTestUtils.invokeMethod(adapter, "parseResponsesResponse", json, "model")
                : ResponsesApiUtil.parseResponse(json, "model");
        assertNotNull(response);
        assertEquals("response-1", response.getRequestId());
        assertEquals("model", response.getModelCode());
        assertEquals("completed", response.getStatus());
        assertEquals(3, response.getOutput().size());
        LlmOutputItem reasoning = response.getOutput().get(0);
        JsonNode original = MAPPER.readTree(json).path("output").path(0);
        assertEquals("reason-1", reasoning.getId());
        assertEquals("reasoning", reasoning.getType());
        assertEquals("completed", reasoning.getStatus());
        assertEquals("opaque", reasoning.getEncryptedContent());
        assertEquals(2, reasoning.getContent().size());
        for (int i = 0; i < reasoning.getContent().size(); i++) {
            assertEquals(original.path("content").path(i).path("type").asText(), reasoning.getContent().get(i).getType());
            assertEquals(original.path("content").path(i).path("text").asText(), reasoning.getContent().get(i).getText());
        }
        assertEquals(1, reasoning.getSummary().size());
        assertEquals("summary_text", reasoning.getSummary().get(0).getType());
        assertEquals("summary", reasoning.getSummary().get(0).getText());
        assertEquals("answer", response.getOutput().get(1).getContent().get(0).getText());
        assertEquals("call-1", response.getOutput().get(2).getCallId());
        assertEquals("lookup", response.getOutput().get(2).getName());
        assertEquals("{}", response.getOutput().get(2).getArguments());
        assertEquals(123L, response.getCreatedAt());
        assertEquals(100, response.getMaxOutputTokens());
        assertEquals(2, response.getUsage().getInputTokens());
        assertEquals(3, response.getUsage().getOutputTokens());
        assertEquals(5, response.getUsage().getTotalTokens());
        if (!(adapter instanceof OpenAIServiceImpl || adapter instanceof DeepseekServiceImpl
                || adapter instanceof DoubaoServiceImpl)) {
            assertEquals(0, response.getUsage().getInputTokensDetails().getCachedTokens());
            assertEquals(1, response.getUsage().getOutputTokensDetails().getReasoningTokens());
        }
    }

    @Test
    void routerRejectsUnsupportedResponsesForSyncAndStreamWithoutSuccess() {
        LlmService unsupported = mock(LlmService.class, CALLS_REAL_METHODS);
        LlmRouter router = new LlmRouter(Map.of("openAIServiceImpl", unsupported));
        LlmResponsesRequest request = responsesRequest();
        BizException syncError = assertThrows(BizException.class, () -> router.responses("OpenAI", request));
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED, syncError.getErrorCode());
        List<String> chunks = new ArrayList<>();
        BizException streamError = assertThrows(BizException.class,
                () -> router.responsesStream("OpenAI", request, chunks::add));
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED, streamError.getErrorCode());
        assertTrue(chunks.isEmpty());
        verify(unsupported).responses(request);
        verify(unsupported).responsesStream(eq(request), any());
    }

    @Test
    void routerRejectsNullSyncResultsForBothProtocols() {
        LlmService service = mock(LlmService.class);
        LlmRouter router = new LlmRouter(Map.of("openAIServiceImpl", service));
        LlmRequest chat = LlmRequest.builder().modelCode("model").build();
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED,
                assertThrows(BizException.class, () -> router.chat("OpenAI", chat)).getErrorCode());
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED,
                assertThrows(BizException.class, () -> router.responses("OpenAI", responsesRequest())).getErrorCode());
        verify(service).chat(chat);
        verify(service).responses(any(LlmResponsesRequest.class));
    }

    private JsonNode chatBody(LlmRequest request) throws IOException {
        String body = ReflectionTestUtils.invokeMethod(deepseek, "buildRequestBody", request, false);
        return MAPPER.readTree(body);
    }

    private static JsonNode responsesBody(LlmService adapter, LlmResponsesRequest request, boolean stream) throws IOException {
        String body = ReflectionTestUtils.invokeMethod(adapter, "buildResponsesRequestBody", request, stream);
        assertNotNull(body);
        return MAPPER.readTree(body);
    }

    private static void assertNoDeepseekInternalKeys(JsonNode body) {
        for (String key : List.of("reasoning_effort", "thinking_type", "skip_temperature", "thinking")) {
            assertFalse(body.has(key), key);
        }
    }

    private static ResponsesTransportTest.Provider provider(LlmService adapter) {
        return ResponsesTransportTest.Provider.valueOf(adapter.getClass().getSimpleName().replace("ServiceImpl", ""));
    }

    private static LlmResponsesRequest responsesRequest(LlmService adapter) {
        LlmResponsesRequest request = responsesRequest();
        if (!provider(adapter).namedChoice()) request.setToolChoice("auto");
        return request;
    }

    private static LlmResponsesRequest responsesRequest() {
        return LlmResponsesRequest.builder().model("model").apiKey("test-key")
                .endpoint("https://example.invalid/responses")
                .input(List.of(LlmResponsesInput.builder().type("reasoning").id("reason-1")
                        .encryptedContent("opaque").summary(List.of(Map.of("type", "summary_text", "text", "history"))).build()))
                .instructions("instructions").temperature(0.5).topP(0.8).maxOutputTokens(123)
                .tools(List.of(Map.of("type", "function", "name", "lookup", "parameters", Map.of("type", "object"))))
                .toolChoice("lookup").build();
    }
}

