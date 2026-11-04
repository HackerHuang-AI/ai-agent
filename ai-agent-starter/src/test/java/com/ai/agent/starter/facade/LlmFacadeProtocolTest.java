package com.ai.agent.starter.facade;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.service.impl.DoubaoServiceImpl;
import com.ai.agent.application.service.impl.OpenAIServiceImpl;
import com.ai.agent.application.utils.ResponsesStreamUtil.CancellableConsumer;
import com.ai.agent.client.dto.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.rpc.CancellationContext;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class LlmFacadeProtocolTest {

    private final LlmRouter router = mock(LlmRouter.class);
    private final LlmFacadeImpl facade = new LlmFacadeImpl(router);
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void chatPreservesReasoningThinkingBlocksAndToolSignaturesInBothDirections() throws Exception {
        List<Map<String, Object>> blocks = List.of(
                Map.of("type", "thinking", "thinking", "think", "signature", "signed", "unknown", List.of(1, 2)),
                Map.of("type", "redacted_thinking", "data", "opaque"));
        Map<String, Object> extra = Map.of("google", Map.of("thought_signature", "google-signed"));
        LlmToolCall toolCall = LlmToolCall.builder().id("call-1").name("weather").arguments("{}")
                .extraContent(extra).build();
        when(router.chat(eq("OpenAI"), any())).thenReturn(LlmResponse.builder().choices(List.of(
                LlmChoice.builder().content("answer").reasoningContent("think").thinkingBlocks(blocks)
                        .toolCalls(List.of(toolCall)).build())).build());
        LlmFacadeRequest request = new LlmFacadeRequest();
        request.setPlatform("OpenAI");
        request.setMessages(List.of(LlmFacadeMessage.user("hello")));
        LlmFacadeResponse response = facade.chat(request);
        assertEquals("think", response.getReasoningContent());
        assertEquals(blocks, response.getThinkingBlocks());
        assertEquals(extra, response.getToolCalls().get(0).getExtraContent());

        LlmFacadeMessage history = LlmFacadeMessage.assistantToolCalls(response.getToolCalls(), response.getContent());
        history.setReasoningContent(response.getReasoningContent());
        history.setThinkingBlocks(response.getThinkingBlocks());
        history = mapper.readValue(mapper.writeValueAsString(history), LlmFacadeMessage.class);
        request.setMessages(List.of(history));
        facade.chat(request);
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router, times(2)).chat(eq("OpenAI"), captured.capture());
        LlmMessage message = captured.getValue().getMessages().get(0);
        assertEquals("answer", message.getTextContent());
        assertEquals("think", message.getReasoningContent());
        assertEquals(blocks, message.getThinkingBlocks());
        assertEquals(extra, message.getToolCalls().get(0).getExtraContent());
        assertEquals("call-1", message.getToolCalls().get(0).getId());
        assertEquals("weather", message.getToolCalls().get(0).getName());
        assertEquals("{}", message.getToolCalls().get(0).getArguments());
        assertNull(new LlmToolCallDto("old", "tool", "{}").getExtraContent());
    }

    @Test
    void rawSnakeCaseStreamToolJsonCanBeReadAsClientDtoAndReplayed() throws Exception {
        String chunk = """
                [TOOL_CALLS][{"id":"call-1","name":"weather","arguments":"{}",
                "extra_content":{"google":{"thought_signature":"signed"}}}]
                """.trim();
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(chunk);
            consumer.accept(null);
            return null;
        }).when(router).chatStream(eq("OpenAI"), any(), any());
        LlmFacadeRequest request = new LlmFacadeRequest();
        request.setPlatform("OpenAI");
        request.setMessages(List.of(LlmFacadeMessage.user("hello")));
        StreamObserver<String> observer = observer();
        facade.chatStream(request, observer);
        ArgumentCaptor<String> emitted = ArgumentCaptor.forClass(String.class);
        verify(observer).onNext(emitted.capture());
        assertEquals(chunk, emitted.getValue());
        List<LlmToolCallDto> calls = mapper.readValue(emitted.getValue().substring("[TOOL_CALLS]".length()),
                mapper.getTypeFactory().constructCollectionType(List.class, LlmToolCallDto.class));
        request.setMessages(List.of(LlmFacadeMessage.assistantToolCalls(calls, null)));
        when(router.chat(eq("OpenAI"), any())).thenReturn(LlmResponse.builder().build());
        facade.chat(request);
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).chat(eq("OpenAI"), captured.capture());
        LlmToolCall call = captured.getValue().getMessages().get(0).getToolCalls().get(0);
        assertEquals("call-1", call.getId());
        assertEquals("weather", call.getName());
        assertEquals("{}", call.getArguments());
        assertEquals(Map.of("google", Map.of("thought_signature", "signed")), call.getExtraContent());
        verify(observer).onCompleted();
    }

    @Test
    void chatForwardsEscapedTextVerbatimForConsumerToDecodeFirstLayer() {
        List<String> texts = List.of("[REASONING]literal", "[THINKING_BLOCKS]literal",
                "[TEXT]literal", "[TEXT][TEXT]literal");
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            texts.forEach(text -> consumer.accept(LlmService.escapeText(text)));
            consumer.accept(null);
            return null;
        }).when(router).chatStream(eq("OpenAI"), any(), any());
        LlmFacadeRequest request = new LlmFacadeRequest();
        request.setPlatform("OpenAI");
        request.setMessages(List.of(LlmFacadeMessage.user("hello")));
        StreamObserver<String> observer = observer();
        facade.chatStream(request, observer);
        var order = inOrder(observer);
        for (String text : texts) {
            String escaped = LlmService.escapeText(text);
            assertTrue(escaped.startsWith("[TEXT]"));
            assertEquals(text, escaped.substring("[TEXT]".length()));
            order.verify(observer).onNext(escaped);
        }
        order.verify(observer).onCompleted();
        order.verifyNoMoreInteractions();
        verify(observer, never()).onError(any());
    }

    @Test
    void responsesRpcCancellationCancelsWrapperAndSuppressesAllLateObserverMessages() {
        RpcContext.restoreCancellationContext(new CancellationContext());
        try {
            StreamObserver<String> observer = observer();
            facade.responsesStream(responsesRequest(), observer);
            ArgumentCaptor<Consumer<String>> captured = ArgumentCaptor.forClass(Consumer.class);
            verify(router).responsesStream(eq("OpenAI"), any(), captured.capture());
            CancellableConsumer consumer = assertInstanceOf(CancellableConsumer.class, captured.getValue());
            String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}";
            assertFalse(consumer.isCancelled());
            consumer.accept(delta);
            verify(observer).onNext(delta);
            clearInvocations(observer);
            assertTrue(RpcContext.getCancellationContext().cancel(new IllegalStateException("client cancelled")));
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
    void responsesAlreadyCancelledRpcContextSkipsRouterAndObserver() {
        RpcContext.restoreCancellationContext(new CancellationContext());
        try {
            assertTrue(RpcContext.getCancellationContext().cancel(new IllegalStateException("client cancelled")));
            StreamObserver<String> observer = observer();
            assertDoesNotThrow(() -> facade.responsesStream(responsesRequest(), observer));
            verifyNoInteractions(router, observer);
        } finally {
            RpcContext.removeCancellationContext();
        }
    }

    @Test
    void responsesPreservesEncryptedReasoningAndReusesRequestMappingForStreaming() throws Exception {
        LlmContentBlock content = LlmContentBlock.builder().type("reasoning_text").text("reason").build();
        LlmContentBlock summary = LlmContentBlock.builder().type("summary_text").text("summary").build();
        when(router.responses(eq("OpenAI"), any())).thenReturn(LlmResponse.builder().output(List.of(
                LlmOutputItem.builder().id("rs-1").type("reasoning").status("completed")
                        .summary(List.of(summary)).content(List.of(content)).encryptedContent("encrypted").build(),
                LlmOutputItem.builder().id("fc-1").type("function_call").callId("call-1")
                        .name("weather").arguments("{}").build(),
                LlmOutputItem.builder().id("msg-1").type("message").role("assistant")
                        .content(List.of(LlmContentBlock.builder().type("output_text").text("answer").build())).build())).build());
        LlmFacadeResponsesRequest request = responsesRequest();
        request.setApiKey("key");
        request.setEndpoint("https://example.com/responses");
        request.setInstructions("instructions");
        request.setTemperature(0.5);
        request.setTopP(0.8);
        request.setMaxOutputTokens(100);
        request.setTools(List.of(Map.of("type", "function", "name", "weather")));
        request.setToolChoice("auto");
        request.setExtraParams(Map.of("store", false));
        List<Map<String, Object>> outputItems = facade.responses(request).getOutput();
        Map<String, Object> outputItem = outputItems.get(0);
        assertInstanceOf(Map.class, ((List<?>) outputItem.get("content")).get(0));
        assertInstanceOf(Map.class, ((List<?>) outputItem.get("summary")).get(0));
        JsonNode output = mapper.valueToTree(outputItem);
        assertEquals("reasoning_text", output.path("content").get(0).path("type").asText());
        assertEquals("reason", output.path("content").get(0).path("text").asText());
        assertEquals("encrypted", output.path("encrypted_content").asText());
        List<LlmFacadeResponsesInput> historyItems = mapper.readValue(mapper.writeValueAsString(outputItems),
                mapper.getTypeFactory().constructCollectionType(List.class, LlmFacadeResponsesInput.class));
        LlmFacadeResponsesInput history = historyItems.get(0);
        request.setInput(historyItems);
        assertFalse(output.has("call_id"));
        assertFalse(output.path("summary").get(0).has("url"));
        assertFalse(output.path("content").get(0).has("url"));
        facade.responses(request);
        facade.responsesStream(request, observer());
        ArgumentCaptor<LlmResponsesRequest> sync = ArgumentCaptor.forClass(LlmResponsesRequest.class);
        ArgumentCaptor<LlmResponsesRequest> stream = ArgumentCaptor.forClass(LlmResponsesRequest.class);
        verify(router, times(2)).responses(eq("OpenAI"), sync.capture());
        verify(router).responsesStream(eq("OpenAI"), stream.capture(), any());
        assertEquals(sync.getValue(), stream.getValue());
        LlmResponsesInput input = stream.getValue().getInput().get(0);
        assertEquals("rs-1", input.getId());
        assertEquals("completed", input.getStatus());
        assertEquals(history.getSummary(), input.getSummary());
        assertEquals("encrypted", input.getEncryptedContent());
        JsonNode json = mapper.valueToTree(input);
        assertEquals("encrypted", json.path("encrypted_content").asText());
        assertFalse(json.has("encryptedContent"));
        assertFalse(json.has("role"));
        assertEquals("reason", json.path("content").get(0).path("text").asText());
        assertFalse(json.path("content").get(0).has("image_url"));
        assertFalse(json.path("content").get(0).has("detail"));
        assertFalse(json.has("call_id"));
        assertEquals(input, mapper.readValue(mapper.writeValueAsString(input), LlmResponsesInput.class));
        String body = ReflectionTestUtils.invokeMethod(mock(OpenAIServiceImpl.class, CALLS_REAL_METHODS),
                "buildResponsesRequestBody", sync.getValue(), false);
        JsonNode official = mapper.readTree(body).path("input");
        assertEquals("encrypted", official.get(0).path("encrypted_content").asText());
        assertEquals("call-1", official.get(1).path("call_id").asText());
        assertEquals("weather", official.get(1).path("name").asText());
        assertEquals("{}", official.get(1).path("arguments").asText());
        assertEquals("answer", official.get(2).path("content").get(0).path("text").asText());
        assertFalse(body.contains("encryptedContent"));
        assertFalse(body.contains("callId"));
        assertFalse(body.contains("\"url\":null"));
        assertFalse(body.contains("\"image_url\":null"));
        assertFalse(body.contains("\"detail\":null"));
    }

    @Test
    void failureEventsAreForwardedBeforeErrorAndNeverCompleted() {
        for (String type : List.of("response.failed", "error")) {
            reset(router);
            String event = "{\"type\":\"" + type + "\",\"error\":{\"message\":\"failed\"}}";
            doAnswer(invocation -> {
                Consumer<String> consumer = invocation.getArgument(2);
                consumer.accept(event);
                consumer.accept(null);
                consumer.accept("{\"type\":\"response.completed\"}");
                throw new IllegalStateException("late error");
            }).when(router).responsesStream(eq("OpenAI"), any(), any());
            StreamObserver<String> observer = observer();
            facade.responsesStream(responsesRequest(), observer);
            var order = inOrder(observer);
            order.verify(observer).onNext(event);
            order.verify(observer).onError(any(RuntimeException.class));
            order.verifyNoMoreInteractions();
            verify(observer, never()).onCompleted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"onNext", "malformed", "[ERROR]"})
    void callbackFailuresEscapeToServiceAndOnlyReportErrorOnce(String failure) {
        String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}";
        String chunk = "malformed".equals(failure) ? "{broken}" : "[ERROR]".equals(failure) ? "[ERROR]" : delta;
        StreamObserver<String> observer = observer();
        IllegalStateException downstream = new IllegalStateException("downstream failed");
        if ("onNext".equals(failure)) doThrow(downstream).when(observer).onNext(delta);
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            RuntimeException cancelled = assertThrows(RuntimeException.class, () -> consumer.accept(chunk));
            assertTrue(assertInstanceOf(CancellableConsumer.class, consumer).isCancelled());
            if ("onNext".equals(failure)) assertSame(downstream, cancelled);
            if ("[ERROR]".equals(failure)) {
                assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, assertInstanceOf(BizException.class, cancelled).getErrorCode());
            }
            consumer.accept("[ERROR]");
            consumer.accept(delta);
            consumer.accept(null);
            throw cancelled;
        }).when(router).responsesStream(eq("OpenAI"), any(), any());
        assertDoesNotThrow(() -> facade.responsesStream(responsesRequest(), observer));
        ArgumentCaptor<Throwable> captured = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captured.capture());
        if ("onNext".equals(failure)) {
            assertSame(downstream, captured.getValue());
            verify(observer).onNext(delta);
        } else if ("[ERROR]".equals(failure)) {
            assertEquals(ErrorCodeEnum.LLM_CALL_FAILED,
                    assertInstanceOf(BizException.class, captured.getValue()).getErrorCode());
        } else {
            assertInstanceOf(com.fasterxml.jackson.core.JsonParseException.class, captured.getValue());
        }
        verifyNoMoreInteractions(observer);
    }

    @Test
    void semanticDoneAndFailureWordsInsideDeltaDoNotTerminateStream() {
        String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"error response.failed\"}";
        String done = "{\"type\":\"response.output_text.done\",\"text\":\"done\"}";
        String completed = "{\"type\":\"response.completed\"}";
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(delta);
            consumer.accept(done);
            consumer.accept(completed);
            consumer.accept(null);
            consumer.accept(null);
            return null;
        }).when(router).responsesStream(eq("OpenAI"), any(), any());
        StreamObserver<String> observer = observer();
        facade.responsesStream(responsesRequest(), observer);
        var order = inOrder(observer);
        order.verify(observer).onNext(delta);
        order.verify(observer).onNext(done);
        order.verify(observer).onNext(completed);
        order.verify(observer).onCompleted();
        order.verifyNoMoreInteractions();
        verify(observer, never()).onError(any());
    }

    @Test
    void unsupportedResponsesThrowsForSyncAndReportsStreamError() {
        LlmService unsupported = mock(LlmService.class, CALLS_REAL_METHODS);
        LlmFacadeImpl provider = new LlmFacadeImpl(new LlmRouter(Map.of("openAIServiceImpl", unsupported)));
        BizException error = assertThrows(BizException.class, () -> provider.responses(responsesRequest()));
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED, error.getErrorCode());
        assertEquals("2002012", error.getErrorCode().getCode());
        StreamObserver<String> observer = observer();
        provider.responsesStream(responsesRequest(), observer);
        ArgumentCaptor<Throwable> captured = ArgumentCaptor.forClass(Throwable.class);
        verify(observer).onError(captured.capture());
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED, ((BizException) captured.getValue()).getErrorCode());
        verify(observer, never()).onNext(any());
        verify(observer, never()).onCompleted();
    }

    @Test
    void typedMessagesMapToOpenAIWireBodyForChatSyncAndStream() throws Exception {
        LlmFacadeRequest request = mapper.readValue("""
                {"platform":"OpenAI","modelCode":"model","temperature":0.5,"extraParams":{"store":false},
                 "messages":[{"role":"user","contents":[
                   {"type":"input_audio","inputAudio":{"data":"YXVkaW8=","format":"wav"}},
                   {"type":"file","file":{"fileId":"file-1"}},
                   {"type":"file","file":{"fileData":"data:application/pdf;base64,cGRm","filename":"report.pdf"}}]}]}
                """, LlmFacadeRequest.class);
        JsonNode expected = mapper.readTree("""
                [{"role":"user","content":[
                  {"type":"input_audio","input_audio":{"data":"YXVkaW8=","format":"wav"}},
                  {"type":"file","file":{"file_id":"file-1"}},
                  {"type":"file","file":{"file_data":"data:application/pdf;base64,cGRm","filename":"report.pdf"}}]}]
                """);
        when(router.chat(eq("OpenAI"), any())).thenReturn(LlmResponse.builder().build());
        facade.chat(request);
        facade.chatStream(request, observer());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).chat(eq("OpenAI"), captured.capture());
        verify(router).chatStream(eq("OpenAI"), captured.capture(), any());
        assertFalse(captured.getAllValues().get(0).isStream());
        assertTrue(captured.getAllValues().get(1).isStream());
        assertEquals(captured.getAllValues().get(0).getMessages(), captured.getAllValues().get(1).getMessages());
        for (LlmRequest value : captured.getAllValues()) {
            List<MessageContent> contents = value.getMessages().get(0).getContents();
            assertEquals("YXVkaW8=", contents.get(0).getInputAudio().getData());
            assertEquals("wav", contents.get(0).getInputAudio().getFormat());
            assertEquals("file-1", contents.get(1).getFile().getFileId());
            assertEquals("data:application/pdf;base64,cGRm", contents.get(2).getFile().getFileData());
            assertEquals("report.pdf", contents.get(2).getFile().getFilename());
            assertEquals("model", value.getModelCode());
            assertEquals(0.5, value.getTemperature());
            assertEquals(request.getExtraParams(), value.getExtraParams());
            String body = ReflectionTestUtils.invokeMethod(mock(OpenAIServiceImpl.class, CALLS_REAL_METHODS),
                    "buildRequestBody", value, value.isStream());
            JsonNode wire = mapper.readTree(body);
            assertEquals(expected, wire.path("messages"));
            assertEquals("model", wire.path("model").asText());
            assertEquals(0.5, wire.path("temperature").asDouble());
            assertEquals(mapper.valueToTree(false), wire.path("store"));
            assertEquals(value.isStream(), wire.path("stream").asBoolean());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OpenAI", "Doubao"})
    void typedMultimodalInputMapsToResponsesWireBodyForSyncAndStream(String platform) throws Exception {
        String content = "Doubao".equals(platform) ? """
                [{"type":"input_video","video_url":"https://example.com/video.mp4","fps":2.0},
                 {"type":"input_audio","audio_url":"https://example.com/audio.wav"},
                 {"type":"input_file","file_id":"file-1"}]
                """ : """
                [{"type":"input_image","image_url":"https://example.com/image.png","detail":"high"},
                 {"type":"input_file","file_url":"https://example.com/report.pdf"},
                 {"type":"input_file","file_data":"data:application/pdf;base64,cGRm","filename":"report.pdf"}]
                """;
        String input = "[{\"type\":\"message\",\"role\":\"user\",\"content\":" + content + "}]";
        LlmFacadeResponsesRequest request = mapper.readValue("{\"platform\":\"" + platform + "\",\"model\":\"model\","
                + "\"instructions\":\"instructions\",\"extraParams\":{\"store\":false},\"input\":" + input + "}",
                LlmFacadeResponsesRequest.class);
        when(router.responses(eq(platform), any())).thenReturn(LlmResponse.builder().build());
        facade.responses(request);
        facade.responsesStream(request, observer());
        ArgumentCaptor<LlmResponsesRequest> captured = ArgumentCaptor.forClass(LlmResponsesRequest.class);
        verify(router).responses(eq(platform), captured.capture());
        verify(router).responsesStream(eq(platform), captured.capture(), any());
        assertEquals(captured.getAllValues().get(0), captured.getAllValues().get(1));
        for (int i = 0; i < captured.getAllValues().size(); i++) {
            LlmResponsesRequest value = captured.getAllValues().get(i);
            assertEquals(mapper.readTree(input), mapper.valueToTree(value.getInput()));
            assertEquals("model", value.getModel());
            assertEquals("instructions", value.getInstructions());
            assertEquals(request.getExtraParams(), value.getExtraParams());
            Object adapter = "Doubao".equals(platform) ? mock(DoubaoServiceImpl.class, CALLS_REAL_METHODS)
                    : mock(OpenAIServiceImpl.class, CALLS_REAL_METHODS);
            String body = ReflectionTestUtils.invokeMethod(adapter, "buildResponsesRequestBody", value, i == 1);
            JsonNode wire = mapper.readTree(body);
            assertEquals(mapper.readTree(input), wire.path("input"));
            assertEquals("model", wire.path("model").asText());
            assertEquals("instructions", wire.path("instructions").asText());
            assertEquals(mapper.valueToTree(false), wire.path("store"));
            assertEquals(i == 1, wire.path("stream").asBoolean());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"nativeMessages", "nativeInput"})
    void removedNativeBodiesAreRejectedByStrictClientDtoDeserialization(String field) {
        Class<?> dto = "nativeMessages".equals(field) ? LlmFacadeRequest.class : LlmFacadeResponsesRequest.class;
        UnrecognizedPropertyException error = assertThrows(UnrecognizedPropertyException.class,
                () -> mapper.readValue("{\"platform\":\"OpenAI\",\"" + field + "\":[{}]}", dto));
        assertEquals(field, error.getPropertyName());
        verifyNoInteractions(router);
    }

    @Test
    void chatRejectsMissingAndEmptyBodiesForSyncAndStream() throws Exception {
        for (String fields : List.of("", ",\"messages\":null", ",\"messages\":[]")) {
            LlmFacadeRequest request = mapper.readValue("{\"platform\":\"OpenAI\"" + fields + "}", LlmFacadeRequest.class);
            assertThrows(IllegalArgumentException.class, () -> facade.chat(request));
            StreamObserver<String> observer = observer();
            assertDoesNotThrow(() -> facade.chatStream(request, observer));
            verify(observer).onError(any(IllegalArgumentException.class));
            verifyNoMoreInteractions(observer);
        }
        verifyNoInteractions(router);
    }

    @Test
    void responsesRejectMissingAndEmptyBodiesForSyncAndStream() throws Exception {
        for (String fields : List.of("", ",\"input\":null", ",\"input\":[]")) {
            LlmFacadeResponsesRequest request = mapper.readValue("{\"platform\":\"OpenAI\"" + fields + "}", LlmFacadeResponsesRequest.class);
            assertThrows(IllegalArgumentException.class, () -> facade.responses(request));
            StreamObserver<String> observer = observer();
            assertDoesNotThrow(() -> facade.responsesStream(request, observer));
            verify(observer).onError(any(IllegalArgumentException.class));
            verifyNoMoreInteractions(observer);
        }
        verifyNoInteractions(router);
    }

    @Test
    void invalidRequestsReportStreamErrorsWithoutEscaping() {
        StreamObserver<String> responsesObserver = observer();
        assertDoesNotThrow(() -> facade.responsesStream(null, responsesObserver));
        verify(responsesObserver).onError(any(IllegalArgumentException.class));
        verify(responsesObserver, never()).onCompleted();
        StreamObserver<String> chatObserver = observer();
        assertDoesNotThrow(() -> facade.chatStream(null, chatObserver));
        verify(chatObserver).onError(any(IllegalArgumentException.class));
        verify(chatObserver, never()).onCompleted();
        verifyNoInteractions(router);
    }

    private static LlmFacadeResponsesRequest responsesRequest() {
        LlmFacadeResponsesInput input = new LlmFacadeResponsesInput();
        input.setType("reasoning");
        LlmFacadeResponsesRequest request = new LlmFacadeResponsesRequest();
        request.setPlatform("OpenAI");
        request.setModel("model");
        request.setInput(List.of(input));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static StreamObserver<String> observer() {
        return mock(StreamObserver.class);
    }
}

