package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.GeminiBO;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.utils.GeminiInteractionsUtil;
import com.ai.agent.application.utils.ResponsesStreamUtil;
import com.ai.agent.infrastructure.config.NacosConfig;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.ai.agent.infrastructure.config.RetryConfig;
import com.ai.agent.infrastructure.config.param.RetryParam;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.enums.OkHttpConfigEnum;
import com.ai.agent.infrastructure.enums.RetryConfigEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GeminiInteractionsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType SSE = MediaType.parse("text/event-stream");
    private static final String ENDPOINT = "https://example.invalid/v1beta/interactions";
    private static final String DELTA = "{\"event_type\":\"step.delta\",\"index\":1,\"delta\":{\"type\":\"arguments_delta\",\"arguments\":\"{\\\"city\\\":\"}}";

    @Test
    void syncPreservesNativeStepsMediaToolsAndPublicParameters() throws Exception {
        Transport transport = new Transport();
        List<Map<String, Object>> media = List.of(
                Map.of("type", "text", "text", "inspect"),
                Map.of("type", "image", "uri", "https://example.invalid/image.png"),
                Map.of("type", "audio", "data", "YWJj", "mime_type", "audio/wav"),
                Map.of("type", "video", "uri", "gs://bucket/video.mp4"),
                Map.of("type", "document", "data", "YWJj", "mime_type", "application/pdf"),
                Map.of("type", "document", "uri", "gs://bucket/data.csv", "mime_type", "text/csv"));
        List<Map<String, Object>> steps = List.of(
                Map.of("type", "user_input", "content", media),
                Map.of("type", "thought", "signature", "opaque-signature", "summary", List.of()),
                Map.of("type", "function_call", "name", "lookup", "id", "call1", "arguments", Map.of("city", "Paris")),
                Map.of("type", "function_result", "call_id", "call1", "result", Map.of("temp", 20)),
                Map.of("type", "model_output", "content", List.of(Map.of("type", "text", "text", "hello"),
                        Map.of("type", "image", "data", "YWJj", "mime_type", "image/png"),
                        Map.of("type", "text", "text", " world"))));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", "i1");
        response.put("model", "gemini-native");
        response.put("status", "completed");
        response.put("created", "2026-11-03T00:00:00Z");
        response.put("updated", "2026-11-03T00:00:01Z");
        response.put("steps", steps);
        response.put("usage", Map.of("total_input_tokens", 10, "total_output_tokens", 20, "total_tokens", 35,
                "total_cached_tokens", 2, "total_thought_tokens", 5));
        ResponseBody body = transport.respond(200, MAPPER.writeValueAsString(response));
        LlmRequest request = request();
        request.setMessages(null);
        request.setInteractionSteps(List.of(
                LlmInteractionStep.builder().type("user_input").content(List.of(
                        MessageContent.ofText("inspect"),
                        MessageContent.builder().type(ContentTypeEnum.IMAGE).uri("https://example.invalid/image.png").build(),
                        MessageContent.builder().type(ContentTypeEnum.AUDIO).data("YWJj").mimeType("audio/wav").build(),
                        MessageContent.builder().type(ContentTypeEnum.VIDEO).uri("gs://bucket/video.mp4").build(),
                        MessageContent.builder().type(ContentTypeEnum.DOCUMENT).data("YWJj").mimeType("application/pdf").build(),
                        MessageContent.builder().type(ContentTypeEnum.DOCUMENT).uri("gs://bucket/data.csv").mimeType("text/csv").build())).build(),
                LlmInteractionStep.builder().type("thought").signature("opaque-signature").summary(List.of()).build(),
                LlmInteractionStep.builder().type("function_call").name("lookup").id("call1").arguments(Map.of("city", "Paris")).build(),
                LlmInteractionStep.builder().type("function_result").callId("call1").result("{\"temp\":20}").build(),
                LlmInteractionStep.builder().type("model_output").content(List.of(MessageContent.ofText("hello"),
                        MessageContent.builder().type(ContentTypeEnum.IMAGE).data("YWJj").mimeType("image/png").build(),
                        MessageContent.ofText(" world"))).build()));
        request.setModelCode("independent-chat-model");
        request.setMaxTokens(456);
        request.setInstructions("system");
        request.setTemperature(0.5);
        request.setTopP(0.8);
        request.setMaxOutputTokens(123);
        request.setGenerationConfig(Map.of("temperature", 0.9, "thinking_level", "high"));
        request.setTools(List.of(Map.of("type", "function", "name", "lookup", "parameters", Map.of("type", "object"))));
        request.setPreviousInteractionId("previous");
        request.setStore(false);
        request.setBackground(true);
        request.setExtraParams(Map.of("legacy_private", "preserved"));

        LlmInteractionsResponse result = transport.service.interactions(request);

        assertEquals(steps, result.getSteps());
        assertEquals("hello world", result.getOutputText());
        assertEquals("2026-11-03T00:00:00Z", result.getCreated());
        assertEquals("2026-11-03T00:00:01Z", result.getUpdated());
        assertEquals("gemini-native", result.getModel());
        assertEquals("i1", result.getId());
        assertEquals(10, result.getUsage().getInputTokens());
        assertEquals(20, result.getUsage().getOutputTokens());
        assertEquals(35, result.getUsage().getTotalTokens());
        assertEquals(2, result.getUsage().getInputTokensDetails().getCachedTokens());
        assertEquals(5, result.getUsage().getOutputTokensDetails().getReasoningTokens());
        Request sent = transport.sent(1);
        assertEquals(ENDPOINT, sent.url().toString());
        assertEquals("POST", sent.method());
        assertEquals("local-key", sent.header("x-goog-api-key"));
        assertNull(sent.header("Authorization"));
        JsonNode json = requestBody(sent);
        var expectedInput = MAPPER.valueToTree(steps);
        ((com.fasterxml.jackson.databind.node.ObjectNode) expectedInput.get(3)).put("result", "{\"temp\":20}");
        assertEquals(expectedInput, json.path("input"));
        assertEquals("native-model", json.path("model").asText());
        assertEquals(456, request.getMaxTokens());
        assertFalse(json.has("maxTokens"));
        assertFalse(json.has("modelCode"));
        assertEquals(MAPPER.valueToTree(request.getTools()), json.path("tools"));
        assertEquals("system", json.path("system_instruction").asText());
        assertEquals(0.5, json.path("generation_config").path("temperature").asDouble());
        assertEquals(0.8, json.path("generation_config").path("top_p").asDouble());
        assertEquals(123, json.path("generation_config").path("max_output_tokens").asInt());
        assertEquals("high", json.path("generation_config").path("thinking_level").asText());
        assertEquals(0.9, request.getGenerationConfig().get("temperature"));
        assertEquals("previous", json.path("previous_interaction_id").asText());
        assertFalse(json.path("store").asBoolean(true));
        assertTrue(json.path("background").asBoolean());
        assertFalse(json.path("stream").asBoolean(true));
        assertEquals("preserved", json.path("legacy_private").asText());
        assertFalse(json.has("apiKey"));
        assertFalse(json.has("endpoint"));
        verifyNoInteractions(transport.nacos);
        verify(body, atLeastOnce()).close();
    }

    @Test
    void typedMessagesPreserveRolesAndContentAndRejectMalformedFieldsBeforeTransport() throws Exception {
        LlmRequest valid = request();
        valid.setMessages(List.of(LlmMessage.ofText("user", "hello"),
                LlmMessage.ofMultiModal("assistant", List.of(MessageContent.builder().type(ContentTypeEnum.TEXT).text("answer").build(),
                        MessageContent.ofImage("data:image/png;base64,YWJj")))));
        JsonNode input = MAPPER.readTree(GeminiInteractionsUtil.buildRequestBody(valid, false)).path("input");
        assertEquals("user_input", input.get(0).path("type").asText());
        assertEquals("hello", input.get(0).path("content").get(0).path("text").asText());
        assertEquals("model_output", input.get(1).path("type").asText());
        assertEquals("answer", input.get(1).path("content").get(0).path("text").asText());
        assertEquals("YWJj", input.get(1).path("content").get(1).path("data").asText());
        assertEquals("image/png", input.get(1).path("content").get(1).path("mime_type").asText());
        for (MessageContent content : List.of(MessageContent.ofText(" "),
                MessageContent.builder().type(ContentTypeEnum.AUDIO).data("YWJj").build(),
                MessageContent.builder().type(ContentTypeEnum.IMAGE).data("data:image/png;base64,YWJj").mimeType("image/png").build(),
                MessageContent.builder().type(ContentTypeEnum.VIDEO).data("%%%").mimeType("video/mp4").build(),
                MessageContent.builder().type(ContentTypeEnum.IMAGE).uri("x").data("YWJj").mimeType("image/png").build(),
                MessageContent.builder().type(ContentTypeEnum.DOCUMENT).data("YWJj").mimeType("text/plain").build(),
                MessageContent.builder().type(ContentTypeEnum.TEXT).value("one").text("two").build(),
                MessageContent.builder().type(ContentTypeEnum.IMAGE).uri("x").detail("high").build())) {
            Transport transport = new Transport();
            LlmRequest request = request();
            request.setMessages(List.of(LlmMessage.ofMultiModal("user", List.of(content))));
            assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertThrows(BizException.class,
                    () -> transport.service.interactions(request)).getErrorCode());
            verifyNoInteractions(transport.client, transport.executor);
        }
        for (LlmInteractionStep step : List.of(
                LlmInteractionStep.builder().type("function_call").name("f").arguments(Map.of()).build(),
                LlmInteractionStep.builder().type("function_result").callId("c").build(),
                LlmInteractionStep.builder().type("function_result").callId("c").result("text").resultContents(List.of(MessageContent.ofText("duplicate"))).build(),
                LlmInteractionStep.builder().type("user_input").content(List.of()).build(),
                LlmInteractionStep.builder().type("text").build(),
                LlmInteractionStep.builder().type("thought").arguments(Map.of()).build())) {
            Transport transport = new Transport();
            LlmRequest request = request();
            request.setMessages(null);
            request.setInteractionSteps(List.of(step));
            assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertThrows(BizException.class,
                    () -> transport.service.interactions(request)).getErrorCode());
            verifyNoInteractions(transport.client, transport.executor);
        }
        LlmRequest both = request();
        both.setInteractionSteps(List.of(LlmInteractionStep.builder().type("thought").signature("sig").build()));
        assertThrows(BizException.class, () -> GeminiInteractionsUtil.buildRequestBody(both, false));
    }

    @Test
    void typedFunctionResultContentsPreserveMediaAndCallId() throws Exception {
        LlmRequest request = request();
        request.setMessages(null);
        request.setInteractionSteps(List.of(LlmInteractionStep.builder().type("function_result").callId("call1")
                .resultContents(List.of(MessageContent.ofText("found"), MessageContent.builder()
                        .type(ContentTypeEnum.IMAGE).uri("gs://bucket/result.png").mimeType("image/png").build())).build()));
        JsonNode step = MAPPER.readTree(GeminiInteractionsUtil.buildRequestBody(request, false)).path("input").get(0);
        assertEquals("call1", step.path("call_id").asText());
        assertEquals("found", step.path("result").get(0).path("text").asText());
        assertEquals("gs://bucket/result.png", step.path("result").get(1).path("uri").asText());
        assertEquals("image/png", step.path("result").get(1).path("mime_type").asText());
        assertFalse(step.has("resultContents"));
    }

    @Test
    void privateParamsCannotOverridePublicBodyOrConfig() {
        for (String field : List.of("model", "input", "stream", "system_instruction", "generation_config", "tools",
                "previous_interaction_id", "continuation_token", "continuationToken", "store", "background", "state", "temperature", "top_p", "max_output_tokens",
                "stop_sequences", "seed", "thinking_level", "thinking_summaries", "tool_choice")) {
            LlmRequest request = request();
            request.setExtraParams(Map.of(field, "override"));
            assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertThrows(BizException.class,
                    () -> GeminiInteractionsUtil.buildRequestBody(request, false)).getErrorCode());
        }
        LlmRequest duplicate = request();
        duplicate.setGenerationConfig(Map.of("native_config", 1));
        duplicate.setExtraParams(Map.of("native_config", 2));
        assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertThrows(BizException.class,
                () -> GeminiInteractionsUtil.buildRequestBody(duplicate, false)).getErrorCode());
    }

    @Test
    void storedContinuationOmitsInputAndPreservesLatestHopWithoutInheritingConfig() throws Exception {
        for (Boolean store : Arrays.asList(null, true)) {
            for (boolean stream : List.of(false, true)) {
                LlmRequest request = request();
                request.setMessages(null);
                request.setPreviousInteractionId("latest-hop-id");
                request.setContinuationToken("opaque+/=next-token");
                request.setStore(store);
                JsonNode body = MAPPER.readTree(GeminiInteractionsUtil.buildRequestBody(request, stream));
                assertFalse(body.has("input"));
                assertEquals("latest-hop-id", body.path("previous_interaction_id").asText());
                assertEquals("opaque+/=next-token", body.path("continuation_token").asText());
                assertEquals(stream, body.path("stream").asBoolean());
                assertFalse(body.has("generation_config"));
                assertEquals(store != null, body.has("store"));
            }
        }
        LlmRequest stateless = request();
        stateless.setStore(false);
        stateless.setContinuationToken("opaque-token");
        JsonNode body = MAPPER.readTree(GeminiInteractionsUtil.buildRequestBody(stateless, false));
        assertEquals("hello", body.path("input").get(0).path("content").get(0).path("text").asText());
        assertEquals("opaque-token", body.path("continuation_token").asText());
        assertFalse(body.has("previous_interaction_id"));
    }

    @Test
    void noInputContinuationRejectsMissingBlankTokenIdOrStoreFalse() {
        for (String token : Arrays.asList(null, "", " ", "valid-token")) {
            for (String id : Arrays.asList(null, "", " ", "valid-id")) {
                for (Boolean store : Arrays.asList(null, true, false)) {
                    if ("valid-token".equals(token) && "valid-id".equals(id) && !Boolean.FALSE.equals(store)) continue;
                    LlmRequest request = request();
                    request.setMessages(null);
                    request.setContinuationToken(token);
                    request.setPreviousInteractionId(id);
                    request.setStore(store);
                    assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertThrows(BizException.class,
                            () -> GeminiInteractionsUtil.buildRequestBody(request, false)).getErrorCode());
                }
            }
        }
        for (List<LlmMessage> input : List.<List<LlmMessage>>of(List.of(), List.of(LlmMessage.ofText("user", "")),
                List.of(LlmMessage.ofText("user", " ")))) {
            LlmRequest request = request();
            request.setMessages(input);
            request.setContinuationToken("valid-token");
            request.setPreviousInteractionId("valid-id");
            assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertThrows(BizException.class,
                    () -> GeminiInteractionsUtil.buildRequestBody(request, false)).getErrorCode());
        }
    }

    @Test
    void synchronousContinuationResponseRetainsTokenStepsUsageAndResumesLatestHop() throws Exception {
        Transport transport = new Transport();
        String body = "{\"id\":\"latest-hop\",\"status\":\"incomplete\",\"continuation_token\":\"opaque+/=token\","
                + "\"steps\":[{\"type\":\"model_output\",\"content\":[{\"type\":\"text\",\"text\":\"partial\"}]}],"
                + "\"usage\":{\"total_input_tokens\":7,\"total_output_tokens\":3,\"total_tokens\":10}}";
        transport.respond(200, body);
        LlmInteractionsResponse result = transport.service.interactions(request());
        assertEquals("opaque+/=token", result.getContinuationToken());
        assertEquals("incomplete", result.getStatus());
        assertEquals("partial", result.getOutputText());
        assertEquals(MAPPER.readTree(body).path("steps"), MAPPER.valueToTree(result.getSteps()));
        assertEquals(7, result.getUsage().getInputTokens());
        assertEquals(3, result.getUsage().getOutputTokens());
        assertEquals(10, result.getUsage().getTotalTokens());
        LlmRequest next = request();
        next.setMessages(null);
        next.setPreviousInteractionId(result.getId());
        next.setContinuationToken(result.getContinuationToken());
        JsonNode resumed = MAPPER.readTree(GeminiInteractionsUtil.buildRequestBody(next, false));
        assertEquals(result.getId(), resumed.path("previous_interaction_id").asText());
        assertEquals(result.getContinuationToken(), resumed.path("continuation_token").asText());
        assertFalse(resumed.has("input"));
        transport.sent(1);
    }

    @Test
    void synchronousTerminalRequiresStepsButBackgroundMayOmitThemAndRootErrorFails() {
        for (String status : List.of("completed", "incomplete", "requires_action", "requires_continuation")) {
            for (String steps : List.of("", ",\"steps\":null", ",\"steps\":{}", ",\"steps\":\"bad\"")) {
                String body = "{\"id\":\"i1\",\"status\":\"" + status + "\"" + steps + "}";
                assertEquals(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED, assertThrows(BizException.class,
                        () -> GeminiInteractionsUtil.parseResponse(body)).getErrorCode());
            }
            assertEquals(status, GeminiInteractionsUtil.parseResponse(
                    "{\"id\":\"i1\",\"status\":\"" + status + "\",\"steps\":[]}").getStatus());
        }
        LlmInteractionsResponse background = GeminiInteractionsUtil.parseResponse("{\"id\":\"i1\",\"status\":\"in_progress\"}");
        assertEquals("in_progress", background.getStatus());
        assertNull(background.getSteps());
        assertNull(background.getContinuationToken());
        assertEquals(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED, assertThrows(BizException.class,
                () -> GeminiInteractionsUtil.parseResponse("{\"status\":\"in_progress\",\"steps\":{}}")).getErrorCode());
        for (String body : List.of("{\"error\":{\"message\":\"failed\"}}",
                "{\"status\":\"completed\",\"steps\":[],\"error\":{\"message\":\"failed\"}}",
                "{\"status\":\"in_progress\",\"error\":null}")) {
            assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, assertThrows(BizException.class,
                    () -> GeminiInteractionsUtil.parseResponse(body)).getErrorCode());
        }
    }

    @Test
    void nacosInteractionsDefaultsAreIndependentOfChat() throws Exception {
        Transport transport = new Transport();
        GeminiBO config = config("native-key", ENDPOINT, "native-model");
        when(transport.nacos.getObject(NacosDataIdEnum.AI_AGENT_GEMINI, "interactions", GeminiBO.class)).thenReturn(config);
        transport.respond(200, "{\"id\":\"i1\",\"status\":\"completed\",\"steps\":[]}");
        transport.service.interactions(LlmRequest.builder().messages(List.of(LlmMessage.ofText("user", "hello"))).build());
        Request sent = transport.sent(1);
        assertEquals("native-key", sent.header("x-goog-api-key"));
        assertEquals(ENDPOINT, sent.url().toString());
        assertEquals("native-model", requestBody(sent).path("model").asText());
        verify(transport.nacos).getObject(NacosDataIdEnum.AI_AGENT_GEMINI, "interactions", GeminiBO.class);
        verifyNoMoreInteractions(transport.nacos);
    }

    @Test
    void fallbackUsesOnlyChatKeyAndOfficialEndpointNeverChatModel() throws Exception {
        Transport transport = new Transport();
        when(transport.nacos.getObject(NacosDataIdEnum.AI_AGENT_GEMINI, "chat", GeminiBO.class))
                .thenReturn(config("chat-key", "https://chat.invalid/completions", "chat-model"));
        when(transport.nacos.getObject(NacosDataIdEnum.AI_AGENT_GEMINI, "interactions", GeminiBO.class))
                .thenReturn(config(null, null, "native-model"));
        transport.respond(200, "{\"status\":\"completed\",\"steps\":[]}");
        transport.service.interactions(LlmRequest.builder().messages(List.of(LlmMessage.ofText("user", "hello"))).build());
        Request sent = transport.sent(1);
        assertEquals("chat-key", sent.header("x-goog-api-key"));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", sent.url().toString());
        assertEquals("native-model", requestBody(sent).path("model").asText());
        Transport missing = new Transport();
        when(missing.nacos.getObject(NacosDataIdEnum.AI_AGENT_GEMINI, "chat", GeminiBO.class))
                .thenReturn(config("chat-key", ENDPOINT, "chat-model"));
        assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertThrows(BizException.class,
                () -> missing.service.interactions(LlmRequest.builder().messages(List.of(LlmMessage.ofText("user", "hello"))).build())).getErrorCode());
        verifyNoInteractions(missing.client);
        assertEquals(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND, assertThrows(BizException.class,
                () -> new Transport().service.interactions(LlmRequest.builder().messages(List.of(LlmMessage.ofText("user", "hello"))).model("m").build())).getErrorCode());
    }

    @Test
    void syncRetainsNonSuccessStatesAndFailsFailedCancelledWithoutRetry() throws Exception {
        for (String status : List.of("completed", "incomplete", "requires_action", "requires_continuation", "in_progress")) {
            Transport transport = new Transport();
            transport.respond(200, "{\"status\":\"" + status + "\",\"steps\":[]}");
            assertEquals(status, transport.service.interactions(request()).getStatus());
            transport.sent(1);
        }
        for (String status : List.of("failed", "cancelled")) {
            Transport transport = new Transport();
            transport.respond(200, "{\"status\":\"" + status + "\"}");
            assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, assertThrows(BizException.class,
                    () -> transport.service.interactions(request())).getErrorCode());
            transport.sent(1);
        }
        Transport malformed = new Transport();
        malformed.respond(200, "{broken}");
        assertEquals(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED, assertThrows(BizException.class,
                () -> malformed.service.interactions(request())).getErrorCode());
        malformed.sent(1);
    }

    @Test
    void streamMergesDataUsesEventFallbackPreservesUnknownAndTerminalUsage() throws Exception {
        Transport transport = new Transport();
        String multiline = "{\"event_type\":\"future.event\",\n\"opaque\":{\"signature\":\"sig\"}}";
        String terminal = "{\"interaction\":{\"status\":\"completed\",\"continuation_token\":\"opaque-next-token\",\"usage\":{\"total_input_tokens\":10}}}";
        ResponseBody body = transport.respond(200, ": comment\n\ndata: " + multiline.replace("\n", "\ndata: ")
                + "\n\n" + frame(DELTA) + "event: interaction.completed\ndata:" + terminal + "\n\ndata:[DONE]\n\n");
        List<String> chunks = transport.stream();
        assertEquals(Arrays.asList(multiline, DELTA), chunks.subList(0, 2));
        assertNull(chunks.get(3));
        JsonNode terminalEvent = MAPPER.readTree(chunks.get(2));
        assertEquals("interaction.completed", terminalEvent.path("event_type").asText());
        assertEquals(MAPPER.readTree(terminal).path("interaction"), terminalEvent.path("interaction"));
        assertFalse(terminalEvent.has("type"));
        Request sent = transport.sent(1);
        assertTrue(requestBody(sent).path("stream").asBoolean());
        assertEquals("text/event-stream", sent.header("Accept"));
        verify(body, atLeastOnce()).close();
    }

    @Test
    void terminalRequiresActionIncompleteAndContinuationEndWithoutInventedSuccess() throws Exception {
        for (String status : List.of("requires_action", "incomplete", "requires_continuation")) {
            Transport transport = new Transport();
            String terminal = completed(status);
            transport.respond(200, frame(terminal));
            assertEquals(Arrays.asList(terminal, null), transport.stream());
            transport.sent(1);
        }
    }

    @Test
    void errorsAreForwardedThenErrorAndNeverRetriedAfterData() throws Exception {
        for (String failure : List.of("{\"event_type\":\"error\",\"error\":{\"message\":\"failed\"}}",
                completed("failed"), completed("cancelled"),
                "{\"event_type\":\"interaction.status_update\",\"interaction\":{\"status\":\"failed\"}}")) {
            Transport transport = new Transport();
            ResponseBody body = transport.respond(200, frame(DELTA) + frame(failure) + frame(completed("completed")));
            assertEquals(List.of(DELTA, failure, "[ERROR]"), transport.stream());
            transport.sent(1);
            verify(body, atLeastOnce()).close();
        }
    }

    @Test
    void eofMalformedLegacyDoneAndUnframedTerminalFailWithoutRetry() throws Exception {
        for (String tail : List.of("", "data:{broken}\n\n", "data:[DONE]\n\n", "data:[]\n\n",
                "data:{}{}\n\n",
                "data:" + completed("completed") + "\n")) {
            Transport transport = new Transport();
            ResponseBody body = transport.respond(200, frame(DELTA) + tail);
            assertEquals(List.of(DELTA, "[ERROR]"), transport.stream());
            transport.sent(1);
            verify(body, atLeastOnce()).close();
        }
    }

    @Test
    void readFailureAfterDeltaDoesNotRetry() throws Exception {
        Transport transport = new Transport();
        Buffer buffer = new Buffer().writeUtf8(frame(DELTA));
        ResponseBody body = spy(ResponseBody.create(SSE, -1, Okio.buffer(new ForwardingSource(buffer) {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                if (buffer.exhausted()) throw new IOException("read failed");
                return super.read(sink, byteCount);
            }
        })));
        when(transport.call.execute()).thenReturn(response(200, body));
        assertEquals(List.of(DELTA, "[ERROR]"), transport.stream());
        transport.sent(1);
        verify(body, atLeastOnce()).close();
    }

    @Test
    void httpFailureNullRetryAndExecutorRejectionConvergeToError() throws Exception {
        Transport transport = new Transport();
        ResponseBody body = transport.respond(503, "{\"error\":{\"message\":\"unavailable\"}}");
        assertEquals(List.of("[ERROR]"), transport.stream());
        transport.sent(2);
        verify(body, atLeastOnce()).close();
        Transport empty = new Transport();
        when(empty.call.execute()).thenReturn(null);
        assertEquals(List.of("[ERROR]"), empty.stream());
        empty.sent(2);
        Transport noBody = new Transport();
        when(noBody.call.execute()).thenReturn(response(200, null));
        assertEquals(List.of("[ERROR]"), noBody.stream());
        noBody.sent(1);
        Transport rejected = new Transport();
        doThrow(new RejectedExecutionException()).when(rejected.executor).submit(any(Runnable.class));
        assertEquals(List.of("[ERROR]"), rejected.stream());
        verifyNoInteractions(rejected.client);
    }

    @Test
    void cancellationBeforeDuringStreamAndDuringRetryStopsCallbacksAndCalls() throws Exception {
        Transport before = new Transport();
        var cancelled = ResponsesStreamUtil.cancellable(chunk -> fail("cancelled callback"));
        cancelled.cancel();
        before.service.interactionsStream(request(), cancelled);
        verifyNoInteractions(before.executor, before.client);
        Transport during = new Transport();
        ResponseBody body = during.respond(200, frame(DELTA) + frame(completed("completed")));
        List<String> chunks = new ArrayList<>();
        var consumer = ResponsesStreamUtil.cancellable(chunks::add);
        during.service.interactionsStream(request(), ResponsesStreamUtil.cancellable(chunk -> {
            chunks.add(chunk);
            throw new IllegalStateException("disconnected");
        }));
        assertEquals(List.of(DELTA), chunks);
        verify(during.call).cancel();
        verify(body, atLeastOnce()).close();
        Transport retry = new Transport();
        when(retry.call.execute()).thenAnswer(invocation -> {
            consumer.cancel();
            throw new IOException("cancelled");
        });
        retry.service.interactionsStream(request(), consumer);
        verify(retry.call).cancel();
        verify(retry.call, times(1)).execute();
        assertEquals(List.of(DELTA), chunks);
    }

    @Test
    void explicitCancellationAfterDeltaStopsStream() throws Exception {
        Transport transport = new Transport();
        ResponseBody body = transport.respond(200, frame(DELTA) + frame(completed("completed")));
        List<String> chunks = new ArrayList<>();
        var reference = new java.util.concurrent.atomic.AtomicReference<ResponsesStreamUtil.CancellableConsumer>();
        var consumer = ResponsesStreamUtil.cancellable(chunk -> {
            chunks.add(chunk);
            reference.get().cancel();
        });
        reference.set(consumer);
        transport.service.interactionsStream(request(), consumer);
        assertEquals(List.of(DELTA), chunks);
        verify(transport.call).cancel();
        transport.sent(1);
        verify(body, atLeastOnce()).close();
    }

    @Test
    void queuedTaskCancellationCancelsFuture() {
        Transport transport = new Transport();
        Future<?> task = mock(Future.class);
        doReturn(task).when(transport.executor).submit(any(Runnable.class));
        var consumer = ResponsesStreamUtil.cancellable(chunk -> fail("queued callback"));
        transport.service.interactionsStream(request(), consumer);
        consumer.cancel();
        verify(task).cancel(true);
        verifyNoInteractions(transport.client);
    }

    @Test
    void routerRejectsNonGeminiAndUsesInteractionsProtocolError() {
        LlmService other = mock(LlmService.class);
        LlmRouter router = new LlmRouter(Map.of("openAIServiceImpl", other));
        BizException failure = assertThrows(BizException.class, () -> router.interactions("OpenAI", request()));
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED, failure.getErrorCode());
        assertArrayEquals(new Object[]{"OpenAI", "Interactions"}, failure.getArgs());
        assertThrows(BizException.class, () -> router.interactionsStream("OpenAI", request(), chunk -> fail()));
        verifyNoInteractions(other);
        LlmService gemini = mock(LlmService.class);
        LlmRouter nativeRouter = new LlmRouter(Map.of("geminiServiceImpl", gemini));
        when(gemini.interactions(any())).thenReturn(null);
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, assertThrows(BizException.class,
                () -> nativeRouter.interactions("Gemini", request())).getErrorCode());
        when(gemini.interactions(any())).thenThrow(new UnsupportedOperationException());
        assertArrayEquals(new Object[]{"Gemini", "Interactions"}, assertThrows(BizException.class,
                () -> nativeRouter.interactions("Gemini", request())).getArgs());
        LlmInteractionsResponse response = LlmInteractionsResponse.builder().status("requires_action").build();
        doReturn(response).when(gemini).interactions(any());
        assertSame(response, nativeRouter.interactions("Gemini", request()));
        LlmService unsupported = mock(LlmService.class, CALLS_REAL_METHODS);
        assertThrows(UnsupportedOperationException.class, () -> unsupported.interactions(request()));
        assertThrows(UnsupportedOperationException.class, () -> unsupported.interactionsStream(request(), chunk -> fail()));
    }

    private static LlmRequest request() {
        return LlmRequest.builder().apiKey("local-key").endpoint(ENDPOINT).model("native-model").messages(List.of(LlmMessage.ofText("user", "hello"))).build();
    }

    private static GeminiBO config(String key, String endpoint, String model) {
        GeminiBO config = new GeminiBO();
        config.setApiKey(key);
        config.setEndpoint(endpoint);
        config.setModelCode(model);
        return config;
    }

    private static String completed(String status) {
        return "{\"event_type\":\"interaction.completed\",\"interaction\":{\"id\":\"i1\",\"status\":\"" + status + "\"}}";
    }

    private static String frame(String data) {
        return "data:" + data + "\n\n";
    }

    private static JsonNode requestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        return MAPPER.readTree(buffer.readUtf8());
    }

    private static Response response(int code, ResponseBody body) {
        return new Response.Builder().request(new Request.Builder().url(ENDPOINT).build())
                .protocol(Protocol.HTTP_1_1).code(code).message("local test").body(body).build();
    }

    private static class Transport {
        private final ExecutorService executor = mock(ExecutorService.class);
        private final OkHttpConfig http = mock(OkHttpConfig.class);
        private final OkHttpClient client = mock(OkHttpClient.class);
        private final Call call = mock(Call.class);
        private final RetryConfig retryConfig = mock(RetryConfig.class);
        private final NacosConfig nacos = mock(NacosConfig.class);
        private final GeminiServiceImpl service;

        private Transport() {
            RetryParam retry = new RetryParam();
            retry.setMaxRetries(1);
            retry.setIntervalMs(0);
            retry.setMaxWaitMs(0);
            when(http.getClientByPlatform(OkHttpConfigEnum.Gemini)).thenReturn(client);
            when(client.newCall(any(Request.class))).thenReturn(call);
            when(retryConfig.getRetryParam(RetryConfigEnum.Gemini)).thenReturn(retry);
            doAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return CompletableFuture.completedFuture(null);
            }).when(executor).submit(any(Runnable.class));
            service = new GeminiServiceImpl(executor, http, retryConfig, nacos);
        }

        private ResponseBody respond(int code, String data) throws IOException {
            ResponseBody body = spy(ResponseBody.create(data, SSE));
            when(call.execute()).thenReturn(response(code, body));
            return body;
        }

        private List<String> stream() {
            List<String> chunks = new ArrayList<>();
            service.interactionsStream(request(), chunks::add);
            return chunks;
        }

        private Request sent(int attempts) throws IOException {
            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(client, times(attempts)).newCall(captor.capture());
            verify(call, times(attempts)).execute();
            return captor.getValue();
        }
    }
}

