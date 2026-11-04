package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmInteractionsResponse;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmUsage;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.utils.GeminiInteractionsUtil;
import com.ai.agent.application.utils.ResponsesStreamUtil.CancellableConsumer;
import com.ai.agent.infrastructure.utils.JsonUtil;
import com.ai.agent.starter.vo.LlmRequestVO;
import com.ai.agent.starter.vo.LlmMessageVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LlmInteractionsControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REQUEST = """
            {"platform":"Gemini","apiKey":"key","endpoint":"https://example.invalid/interactions","model":"model",
             "interactionSteps":[{"type":"thought","signature":"signed","summary":[{"type":"TEXT","text":"summary"}]}],
             "modelCode":"chat-model","maxTokens":42,
             "instructions":"instructions","temperature":0.5,"topP":0.8,"maxOutputTokens":123,
             "tools":[{"type":"function","name":"lookup"}],"generationConfig":{"thinking_level":"high"},
             "previousInteractionId":"previous","continuationToken":"token","store":false,"background":true,"extraParams":{"vendor":true}}
            """;
    private final LlmRouter router = mock(LlmRouter.class);
    private final LlmChatController controller = new LlmChatController(router);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        JsonUtil.init(MAPPER);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void syncReturnsDomainPayloadAndSyncStreamMapAllFieldsIdentically() throws Exception {
        List<Map<String, Object>> steps = List.of(Map.of("type", "thought", "signature", "signed", "unknown", List.of(1, 2)));
        LlmInteractionsResponse response = LlmInteractionsResponse.builder().id("interaction-1").model("model")
                .status("completed").created("created").updated("updated").steps(steps).outputText("answer").continuationToken("next-token")
                .usage(LlmUsage.builder().inputTokens(1).outputTokens(2).totalTokens(3).build()).build();
        when(router.interactions(eq("Gemini"), any())).thenReturn(response);
        MvcResult result = mvc.perform(post("/api/llm/interactions").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(status().isOk()).andReturn();
        assertEquals(MAPPER.valueToTree(response), MAPPER.readTree(result.getResponse().getContentAsString()).path("data"));
        assertEquals("next-token", MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("continuationToken").asText());
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(null);
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        assertEquals("", streamBody());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).interactions(eq("Gemini"), captured.capture());
        verify(router).interactionsStream(eq("Gemini"), captured.capture(), any());
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(REQUEST);
        expected.remove("platform");
        expected.set("interactionSteps", MAPPER.valueToTree(MAPPER.readValue(REQUEST, LlmRequestVO.class).getInteractionSteps()));
        for (LlmRequest value : captured.getAllValues()) {
            var actual = MAPPER.valueToTree(value);
            expected.fields().forEachRemaining(field -> assertEquals(field.getValue(), actual.get(field.getKey()), field.getKey()));
            assertNull(value.getMessages());
            assertEquals("signed", value.getInteractionSteps().get(0).getSignature());
        }
    }

    @Test
    void sharedMessagesContentsMapForSyncAndStream() throws Exception {
        String body = "{\"platform\":\"Gemini\",\"messages\":[{\"role\":\"user\",\"contents\":[{\"type\":\"TEXT\",\"text\":\"hello\"},"
                + "{\"type\":\"IMAGE\",\"uri\":\"gs://bucket/image.png\",\"mimeType\":\"image/png\"}]}]}";
        when(router.interactions(eq("Gemini"), any())).thenReturn(LlmInteractionsResponse.builder().build());
        doAnswer(invocation -> {
            invocation.getArgument(2, Consumer.class).accept(null);
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        mvc.perform(post("/api/llm/interactions").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        MvcResult stream = mvc.perform(post("/api/llm/interactions/stream").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).interactions(eq("Gemini"), captured.capture());
        verify(router).interactionsStream(eq("Gemini"), captured.capture(), any());
        for (LlmRequest value : captured.getAllValues()) {
            assertNull(value.getInteractionSteps());
            assertEquals("user", value.getMessages().get(0).getRole());
            assertEquals("hello", value.getMessages().get(0).getContents().get(0).getText());
            assertEquals("gs://bucket/image.png", value.getMessages().get(0).getContents().get(1).getUri());
            assertEquals("image/png", value.getMessages().get(0).getContents().get(1).getMimeType());
        }
    }

    @Test
    void streamPreservesNativeJsonEventNamesAndCompletesOnlyOnNull() throws Exception {
        String delta = "{\"event_type\":\"content.delta\",\"delta\":{\"signature\":\"signed\",\"text\":\"error failed cancelled\"}}";
        String completed = "{\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"completed\"}}";
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(delta);
            consumer.accept(completed);
            consumer.accept(null);
            consumer.accept(null);
            consumer.accept("[ERROR]");
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        assertEquals("event:content.delta\ndata:" + delta + "\n\nevent:interaction.completed\ndata:" + completed + "\n\n", streamBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"event_type\":\"error\",\"error\":{\"message\":\"failed\"}}",
            "{\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"failed\"}}",
            "{\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"cancelled\"}}",
            "{\"event_type\":\"interaction.status\",\"status\":\"failed\"}",
            "{\"event_type\":\"interaction.status\",\"status\":\"cancelled\"}",
            "{\"event_type\":\"interaction.failed\"}", "{\"event_type\":\"interaction.cancelled\"}"})
    void failurePayloadIsSentOnceThenTerminates(String failure) throws Exception {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(failure);
            consumer.accept("[ERROR]");
            consumer.accept(null);
            consumer.accept(null);
            consumer.accept("{\"event_type\":\"content.delta\"}");
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        String type = MAPPER.readTree(failure).path("event_type").asText();
        assertEquals("event:" + type + "\ndata:" + failure + "\n\n", streamBody());
    }

    @Test
    void errorSentinelBecomesSingleErrorEventWithoutDone() throws Exception {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept("[ERROR]");
            consumer.accept("[ERROR]");
            consumer.accept(null);
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        assertEquals("event:error\ndata:{\"event_type\":\"error\",\"code\":\"" + ErrorCodeEnum.LLM_CALL_FAILED.getCode()
                + "\",\"message\":\"LLM call failed\"}\n\n", streamBody());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onComplete", "onTimeout"})
    void lifecycleCancelsUpstreamAndSuppressesLateSignals(String lifecycle) throws Exception {
        MvcResult initial = mvc.perform(post("/api/llm/interactions/stream").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(request().asyncStarted()).andReturn();
        ArgumentCaptor<Consumer<String>> captured = ArgumentCaptor.forClass(Consumer.class);
        verify(router).interactionsStream(eq("Gemini"), any(), captured.capture());
        CancellableConsumer consumer = assertInstanceOf(CancellableConsumer.class, captured.getValue());
        String delta = "{\"event_type\":\"content.delta\",\"delta\":\"hello\"}";
        consumer.accept(delta);
        MockAsyncContext context = assertInstanceOf(MockAsyncContext.class, initial.getRequest().getAsyncContext());
        assertFalse(context.getListeners().isEmpty());
        AsyncEvent event = new AsyncEvent(context, initial.getRequest(), initial.getResponse(), new IOException("disconnected"));
        for (AsyncListener listener : List.copyOf(context.getListeners())) {
            switch (lifecycle) {
                case "onError" -> listener.onError(event);
                case "onComplete" -> listener.onComplete(event);
                case "onTimeout" -> listener.onTimeout(event);
            }
        }
        assertTrue(consumer.isCancelled());
        String body = initial.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> {
            consumer.accept(delta);
            consumer.accept("[ERROR]");
            consumer.accept("{broken}");
            consumer.accept(null);
        });
        assertEquals(body, initial.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"send", "malformed"})
    void sendAndParseErrorsCancelAndTerminateOnce(String failure) throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        IOException disconnected = new IOException("disconnected");
        if ("send".equals(failure)) doThrow(disconnected).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        Consumer<String> downstream = ReflectionTestUtils.invokeMethod(controller, "buildInteractionsSseConsumer",
                emitter, "Gemini", new AtomicBoolean());
        CancellableConsumer consumer = new CancellableConsumer(downstream);
        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> consumer.accept("send".equals(failure) ? "{\"event_type\":\"content.delta\"}" : "{broken}"));
        assertTrue(consumer.isCancelled());
        consumer.accept("[ERROR]");
        consumer.accept(null);
        verify(emitter).completeWithError(error.getCause());
        verify(emitter, never()).complete();
        if ("send".equals(failure)) assertSame(disconnected, error.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"messages\":null", ",\"messages\":[]", ",\"interactionSteps\":[]",
            ",\"messages\":[{\"role\":\"user\",\"value\":\"hello\"}],\"interactionSteps\":[]",
            ",\"messages\":[],\"interactionSteps\":[{\"type\":\"thought\",\"signature\":\"sig\"}]",
            ",\"messages\":[{\"role\":\"user\",\"contents\":[{\"type\":\"TEXT\",\"value\":\"hello\"}]}],\"interactionSteps\":[{\"type\":\"thought\",\"signature\":\"sig\"}]"})
    void missingEmptyOrConflictingInputIsRejectedAfterRoutingForBothEndpoints(String input) throws Exception {
        assertPlatformRejectsBothEndpoints("{\"platform\":\"Gemini\"" + input + "}");
    }

    @Test
    void blankRoleFailsCommonHttpValidationForBothEndpoints() throws Exception {
        for (String path : List.of("/api/llm/interactions", "/api/llm/interactions/stream")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"platform\":\"Gemini\",\"messages\":[{\"role\":\" \"}]}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(router);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"messages\":[]", "\"interactionSteps\":[]",
            "\"messages\":[{\"role\":\"user\",\"value\":\"hello\"}],\"interactionSteps\":[]",
            "\"messages\":[],\"interactionSteps\":[{\"type\":\"thought\",\"signature\":\"sig\"}]"})
    void validContinuationDoesNotBypassEmptyOrConflictingBodies(String input) throws Exception {
        String body = "{\"platform\":\"Gemini\",\"continuationToken\":\"token\",\"previousInteractionId\":\"previous\",\"store\":true,"
                + input + "}";
        assertPlatformRejectsBothEndpoints(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"store\":true"})
    void continuationWithoutInputIsAcceptedAndMappedForBothEndpoints(String store) throws Exception {
        String body = "{\"platform\":\"Gemini\",\"continuationToken\":\"token\",\"previousInteractionId\":\"previous\"" + store + "}";
        when(router.interactions(eq("Gemini"), any())).thenReturn(LlmInteractionsResponse.builder().continuationToken("next-token").build());
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(null);
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        MvcResult sync = mvc.perform(post("/api/llm/interactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        assertEquals("next-token", MAPPER.readTree(sync.getResponse().getContentAsString()).path("data").path("continuationToken").asText());
        MvcResult stream = mvc.perform(post("/api/llm/interactions/stream").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).interactions(eq("Gemini"), captured.capture());
        verify(router).interactionsStream(eq("Gemini"), captured.capture(), any());
        for (LlmRequest value : captured.getAllValues()) {
            assertNull(value.getMessages());
            assertNull(value.getInteractionSteps());
            assertEquals("token", value.getContinuationToken());
            assertEquals("previous", value.getPreviousInteractionId());
            assertEquals(store.isEmpty() ? null : Boolean.TRUE, value.getStore());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"previousInteractionId\":\"previous\"",
            "\"continuationToken\":\"token\"",
            "\"continuationToken\":\"\",\"previousInteractionId\":\"previous\"",
            "\"continuationToken\":\"  \",\"previousInteractionId\":\"previous\"",
            "\"continuationToken\":\"token\",\"previousInteractionId\":\"\"",
            "\"continuationToken\":\"token\",\"previousInteractionId\":\"  \"",
            "\"continuationToken\":\"token\",\"previousInteractionId\":\"previous\",\"store\":false"})
    void incompleteOrUnstoredContinuationWithoutInputIsRejected(String fields) throws Exception {
        assertPlatformRejectsBothEndpoints("{\"platform\":\"Gemini\"," + fields + "}");
    }

    @Test
    void routingFailureEscapesBothEndpointsWithoutSuccessPayload() throws Exception {
        BizException failure = new BizException(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED);
        when(router.interactions(eq("Gemini"), any())).thenThrow(failure);
        doThrow(failure).when(router).interactionsStream(eq("Gemini"), any(), any());
        LlmRequestVO request = MAPPER.readValue(REQUEST, LlmRequestVO.class);
        assertSame(failure, assertThrows(BizException.class, () -> controller.interactions(request)));
        assertSame(failure, assertThrows(BizException.class, () -> controller.interactionsStream(request)));
    }

    @Test
    void blankPlatformIsRejectedBeforeRouting() {
        LlmRequestVO request = new LlmRequestVO();
        request.setPlatform(" ");
        request.setMessages(List.of(new LlmMessageVO()));
        assertThrows(BizException.class, () -> controller.interactions(request));
        assertThrows(BizException.class, () -> controller.interactionsStream(request));
        verifyNoInteractions(router);
    }

    private void assertPlatformRejectsBothEndpoints(String body) throws Exception {
        when(router.interactions(eq("Gemini"), any())).thenAnswer(invocation -> {
            GeminiInteractionsUtil.buildRequestBody(invocation.getArgument(1), false);
            return LlmInteractionsResponse.builder().build();
        });
        doAnswer(invocation -> {
            GeminiInteractionsUtil.buildRequestBody(invocation.getArgument(1), true);
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        LlmRequestVO request = MAPPER.readValue(body, LlmRequestVO.class);
        assertEquals(ErrorCodeEnum.PARAM_ILLEGAL,
                assertThrows(BizException.class, () -> controller.interactions(request)).getErrorCode());
        assertEquals(ErrorCodeEnum.PARAM_ILLEGAL,
                assertThrows(BizException.class, () -> controller.interactionsStream(request)).getErrorCode());
        verify(router).interactions(eq("Gemini"), any());
        verify(router).interactionsStream(eq("Gemini"), any(), any());
    }

    private String streamBody() throws Exception {
        MvcResult initial = mvc.perform(post("/api/llm/interactions/stream").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}

