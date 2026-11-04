package com.ai.agent.starter.facade;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmInputTokensDetails;
import com.ai.agent.application.model.llm.LlmInteractionsResponse;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmUsage;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.utils.GeminiInteractionsUtil;
import com.ai.agent.application.utils.ResponsesStreamUtil.CancellableConsumer;
import com.ai.agent.client.dto.LlmFacadeInteractionsResponse;
import com.ai.agent.client.dto.LlmFacadeRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.rpc.CancellationContext;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LlmInteractionsFacadeTest {

    private final LlmRouter router = mock(LlmRouter.class);
    private final LlmFacadeImpl facade = new LlmFacadeImpl(router);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void syncPreservesStepsSignaturesUsageDetailsAndAllRequestFields() throws Exception {
        LlmFacadeRequest request = request();
        List<Map<String, Object>> steps = List.of(Map.of("type", "thought", "signature", "signed", "unknown", List.of(1, 2)));
        LlmUsage usage = LlmUsage.builder().inputTokens(2).outputTokens(3).totalTokens(5)
                .inputTokensDetails(LlmInputTokensDetails.builder().cachedTokens(1).build()).build();
        LlmInteractionsResponse response = LlmInteractionsResponse.builder().id("interaction-1").model("model")
                .status("completed").created("created").updated("updated").steps(steps).outputText("answer")
                .continuationToken("next-token").usage(usage).build();
        when(router.interactions(eq("Gemini"), any())).thenReturn(response);
        LlmFacadeInteractionsResponse result = facade.interactions(request);
        assertEquals(response.getId(), result.getId());
        assertEquals(response.getModel(), result.getModel());
        assertEquals(response.getStatus(), result.getStatus());
        assertEquals(response.getCreated(), result.getCreated());
        assertEquals(response.getUpdated(), result.getUpdated());
        assertSame(steps, result.getSteps());
        assertEquals("answer", result.getOutputText());
        assertEquals("next-token", result.getContinuationToken());
        assertEquals(2, result.getInputTokens());
        assertEquals(3, result.getOutputTokens());
        assertEquals(5, result.getTotalTokens());
        assertEquals(mapper.convertValue(usage, new TypeReference<Map<String, Object>>() {}), result.getUsage());
        assertInstanceOf(Map.class, result.getUsage().get("inputTokensDetails"));
        facade.interactionsStream(request, observer());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).interactions(eq("Gemini"), captured.capture());
        verify(router).interactionsStream(eq("Gemini"), captured.capture(), any());
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(request);
        expected.remove("platform");
        for (LlmRequest value : captured.getAllValues()) {
            var actual = mapper.valueToTree(value);
            expected.fields().forEachRemaining(field -> assertEquals(field.getValue(), actual.get(field.getKey()), field.getKey()));
            assertEquals("signed", value.getInteractionSteps().get(0).getSignature());
            assertEquals("summary", value.getInteractionSteps().get(0).getSummary().get(0).getText());
        }
        assertEquals(mapper.valueToTree(request), mapper.valueToTree(roundTrip(request)));
        assertEquals(mapper.valueToTree(result), mapper.valueToTree(roundTrip(result)));
    }

    @Test
    void sharedMessagesContentsMapForSyncStreamAndSerialization() throws Exception {
        LlmFacadeRequest request = request();
        request.setInteractionSteps(null);
        com.ai.agent.client.dto.LlmFacadeContent text = new com.ai.agent.client.dto.LlmFacadeContent();
        text.setType("TEXT");
        text.setText("hello");
        com.ai.agent.client.dto.LlmFacadeMessage message = new com.ai.agent.client.dto.LlmFacadeMessage();
        message.setRole("user");
        message.setContents(List.of(text));
        request.setMessages(List.of(message));
        when(router.interactions(eq("Gemini"), any())).thenReturn(LlmInteractionsResponse.builder().build());
        facade.interactions(request);
        facade.interactionsStream(request, observer());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).interactions(eq("Gemini"), captured.capture());
        verify(router).interactionsStream(eq("Gemini"), captured.capture(), any());
        for (LlmRequest value : captured.getAllValues()) {
            assertNull(value.getInteractionSteps());
            assertEquals("user", value.getMessages().get(0).getRole());
            assertEquals("hello", value.getMessages().get(0).getContents().get(0).getText());
        }
        assertEquals(mapper.valueToTree(request), mapper.valueToTree(roundTrip(request)));
    }

    @Test
    void absentUsageStaysAbsentRatherThanFabricatingTokenCounts() {
        when(router.interactions(eq("Gemini"), any())).thenReturn(LlmInteractionsResponse.builder().build());
        LlmFacadeInteractionsResponse result = facade.interactions(request());
        assertNull(result.getUsage());
        assertNull(result.getInputTokens());
        assertNull(result.getOutputTokens());
        assertNull(result.getTotalTokens());
    }

    @Test
    void streamForwardsRawJsonAndOnlyNullCompletesOnce() {
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
        StreamObserver<String> observer = observer();
        facade.interactionsStream(request(), observer);
        var order = inOrder(observer);
        order.verify(observer).onNext(delta);
        order.verify(observer).onNext(completed);
        order.verify(observer).onCompleted();
        order.verifyNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"event_type\":\"error\",\"error\":{\"message\":\"failed\"}}",
            "{\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"failed\"}}",
            "{\"event_type\":\"interaction.completed\",\"interaction\":{\"status\":\"cancelled\"}}",
            "{\"event_type\":\"interaction.status\",\"status\":\"failed\"}",
            "{\"event_type\":\"interaction.status\",\"status\":\"cancelled\"}",
            "{\"event_type\":\"interaction.failed\"}", "{\"event_type\":\"interaction.cancelled\"}"})
    void failureIsForwardedBeforeSingleErrorAndNeverCompleted(String failure) {
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(failure);
            consumer.accept("[ERROR]");
            consumer.accept(null);
            consumer.accept("{\"event_type\":\"content.delta\"}");
            throw new IllegalStateException("late failure");
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        StreamObserver<String> observer = observer();
        facade.interactionsStream(request(), observer);
        var order = inOrder(observer);
        order.verify(observer).onNext(failure);
        order.verify(observer).onError(any(BizException.class));
        order.verifyNoMoreInteractions();
        verify(observer, never()).onCompleted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"onNext", "malformed", "[ERROR]"})
    void callbackParseAndSentinelFailuresCancelAndReportOnlyOnce(String failure) {
        String delta = "{\"event_type\":\"content.delta\"}";
        String chunk = "malformed".equals(failure) ? "{broken}" : "[ERROR]".equals(failure) ? "[ERROR]" : delta;
        StreamObserver<String> observer = observer();
        IllegalStateException downstream = new IllegalStateException("downstream failed");
        if ("onNext".equals(failure)) doThrow(downstream).when(observer).onNext(delta);
        doAnswer(invocation -> {
            CancellableConsumer consumer = assertInstanceOf(CancellableConsumer.class, invocation.getArgument(2));
            RuntimeException error = assertThrows(RuntimeException.class, () -> consumer.accept(chunk));
            assertTrue(consumer.isCancelled());
            consumer.accept("[ERROR]");
            consumer.accept(null);
            throw error;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        assertDoesNotThrow(() -> facade.interactionsStream(request(), observer));
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        if ("onNext".equals(failure)) {
            assertSame(downstream, error.getValue());
            verify(observer).onNext(delta);
        } else if ("[ERROR]".equals(failure)) {
            assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, assertInstanceOf(BizException.class, error.getValue()).getErrorCode());
        }
        verifyNoMoreInteractions(observer);
    }

    @Test
    void rpcCancellationCancelsUpstreamAndSuppressesLateCallbacks() {
        RpcContext.restoreCancellationContext(new CancellationContext());
        try {
            StreamObserver<String> observer = observer();
            facade.interactionsStream(request(), observer);
            ArgumentCaptor<Consumer<String>> captured = ArgumentCaptor.forClass(Consumer.class);
            verify(router).interactionsStream(eq("Gemini"), any(), captured.capture());
            CancellableConsumer consumer = assertInstanceOf(CancellableConsumer.class, captured.getValue());
            String delta = "{\"event_type\":\"content.delta\"}";
            consumer.accept(delta);
            verify(observer).onNext(delta);
            clearInvocations(observer);
            assertTrue(RpcContext.getCancellationContext().cancel(new IllegalStateException("cancelled")));
            assertTrue(consumer.isCancelled());
            assertDoesNotThrow(() -> {
                consumer.accept(delta);
                consumer.accept("[ERROR]");
                consumer.accept("{broken}");
                consumer.accept(null);
            });
            verifyNoInteractions(observer);
        } finally {
            RpcContext.removeCancellationContext();
        }
    }

    @Test
    void alreadyCancelledRpcSkipsRouterAndObserver() {
        RpcContext.restoreCancellationContext(new CancellationContext());
        try {
            RpcContext.getCancellationContext().cancel(new IllegalStateException("cancelled"));
            StreamObserver<String> observer = observer();
            facade.interactionsStream(request(), observer);
            verifyNoInteractions(router, observer);
        } finally {
            RpcContext.removeCancellationContext();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OpenAI", "Gemini"})
    void unsupportedPlatformOrProtocolFailsSyncAndStream(String platform) {
        LlmService unsupported = mock(LlmService.class, CALLS_REAL_METHODS);
        LlmFacadeImpl provider = new LlmFacadeImpl(new LlmRouter(Map.of(
                "openAIServiceImpl", unsupported, "geminiServiceImpl", unsupported)));
        LlmFacadeRequest request = request();
        request.setPlatform(platform);
        BizException sync = assertThrows(BizException.class, () -> provider.interactions(request));
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED, sync.getErrorCode());
        StreamObserver<String> observer = observer();
        provider.interactionsStream(request, observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED, assertInstanceOf(BizException.class, error.getValue()).getErrorCode());
        verifyNoMoreInteractions(observer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"store\":true"})
    void continuationWithoutInputIsAcceptedAndMappedForSyncAndStream(String store) throws Exception {
        LlmFacadeRequest request = mapper.readValue(
                "{\"platform\":\"Gemini\",\"continuationToken\":\"token\",\"previousInteractionId\":\"previous\"" + store + "}",
                LlmFacadeRequest.class);
        when(router.interactions(eq("Gemini"), any())).thenReturn(LlmInteractionsResponse.builder().continuationToken("next-token").build());
        assertEquals("next-token", facade.interactions(request).getContinuationToken());
        facade.interactionsStream(request, observer());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).interactions(eq("Gemini"), captured.capture());
        verify(router).interactionsStream(eq("Gemini"), captured.capture(), any());
        for (LlmRequest value : captured.getAllValues()) {
            assertNull(value.getMessages());
            assertNull(value.getInteractionSteps());
            assertEquals("token", value.getContinuationToken());
            assertEquals("previous", value.getPreviousInteractionId());
            assertEquals(request.getStore(), value.getStore());
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
            "\"continuationToken\":\"token\",\"previousInteractionId\":\"previous\",\"store\":false",
            "\"continuationToken\":\"token\",\"previousInteractionId\":\"previous\",\"messages\":[]",
            "\"continuationToken\":\"token\",\"previousInteractionId\":\"previous\",\"interactionSteps\":[]",
            "\"messages\":[],\"interactionSteps\":[{\"type\":\"thought\",\"signature\":\"signed\"}]",
            "\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"interactionSteps\":[]"})
    void incompleteOrUnstoredContinuationWithoutInputIsRejected(String fields) throws Exception {
        LlmFacadeRequest request = mapper.readValue("{\"platform\":\"Gemini\"," + fields + "}", LlmFacadeRequest.class);
        assertPlatformRejectsSyncAndStream(request);
    }

    @Test
    void nullRequestAndBlankPlatformFailWithoutRouting() {
        LlmFacadeRequest blank = request();
        blank.setPlatform(" ");
        for (LlmFacadeRequest request : new LlmFacadeRequest[]{null, blank}) {
            assertThrows(IllegalArgumentException.class, () -> facade.interactions(request));
            StreamObserver<String> observer = observer();
            facade.interactionsStream(request, observer);
            verify(observer).onError(any(IllegalArgumentException.class));
            verifyNoMoreInteractions(observer);
        }
        verifyNoInteractions(router);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingInput", "both", "empty"})
    void missingEmptyOrConflictingInputIsRejectedAfterRouting(String input) {
        LlmFacadeRequest request = request();
        switch (input) {
            case "missingInput" -> request.setInteractionSteps(null);
            case "both" -> request.setMessages(List.of(com.ai.agent.client.dto.LlmFacadeMessage.user("hello")));
            case "empty" -> request.setInteractionSteps(List.of());
        }
        assertPlatformRejectsSyncAndStream(request);
    }

    private void assertPlatformRejectsSyncAndStream(LlmFacadeRequest request) {
        when(router.interactions(eq("Gemini"), any())).thenAnswer(invocation -> {
            GeminiInteractionsUtil.buildRequestBody(invocation.getArgument(1), false);
            return LlmInteractionsResponse.builder().build();
        });
        doAnswer(invocation -> {
            GeminiInteractionsUtil.buildRequestBody(invocation.getArgument(1), true);
            return null;
        }).when(router).interactionsStream(eq("Gemini"), any(), any());
        assertEquals(ErrorCodeEnum.PARAM_ILLEGAL,
                assertThrows(BizException.class, () -> facade.interactions(request)).getErrorCode());
        StreamObserver<String> observer = observer();
        facade.interactionsStream(request, observer);
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(error.capture());
        assertEquals(ErrorCodeEnum.PARAM_ILLEGAL, assertInstanceOf(BizException.class, error.getValue()).getErrorCode());
        verifyNoMoreInteractions(observer);
        verify(router).interactions(eq("Gemini"), any());
        verify(router).interactionsStream(eq("Gemini"), any(), any());
    }

    private static LlmFacadeRequest request() {
        LlmFacadeRequest request = new LlmFacadeRequest();
        request.setPlatform("Gemini");
        request.setApiKey("key");
        request.setEndpoint("https://example.invalid/interactions");
        request.setModel("model");
        com.ai.agent.client.dto.LlmFacadeContent summary = new com.ai.agent.client.dto.LlmFacadeContent();
        summary.setType("TEXT");
        summary.setText("summary");
        request.setInteractionSteps(List.of(com.ai.agent.client.dto.LlmFacadeInteractionStep.builder()
                .type("thought").signature("signed").summary(List.of(summary)).build()));
        request.setModelCode("chat-model");
        request.setMaxTokens(42);
        request.setInstructions("instructions");
        request.setTemperature(0.5);
        request.setTopP(0.8);
        request.setMaxOutputTokens(123);
        request.setTools(List.of(Map.of("type", "function", "name", "lookup")));
        request.setGenerationConfig(Map.of("thinking_level", "high"));
        request.setPreviousInteractionId("previous");
        request.setContinuationToken("token");
        request.setStore(false);
        request.setBackground(true);
        request.setExtraParams(Map.of("vendor", true));
        return request;
    }

    private static Object roundTrip(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(value);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readObject();
        }
    }

    @SuppressWarnings("unchecked")
    private static StreamObserver<String> observer() {
        return mock(StreamObserver.class);
    }
}

