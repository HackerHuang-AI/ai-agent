package com.ai.agent.application.service.impl;

import com.ai.agent.application.bo.*;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.LlmResponsesContent;
import com.ai.agent.application.model.llm.LlmResponsesInput;
import com.ai.agent.application.model.llm.LlmResponsesRequest;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.utils.ResponsesStreamUtil;
import com.ai.agent.application.utils.ResponsesStreamUtil.CancellableConsumer;
import com.ai.agent.client.dto.LlmFacadeResponsesContent;
import com.ai.agent.client.dto.LlmFacadeResponsesInput;
import com.ai.agent.client.dto.LlmFacadeResponsesRequest;
import com.ai.agent.infrastructure.config.NacosConfig;
import com.ai.agent.infrastructure.config.OkHttpConfig;
import com.ai.agent.infrastructure.config.RetryConfig;
import com.ai.agent.infrastructure.config.param.RetryParam;
import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.enums.OkHttpConfigEnum;
import com.ai.agent.infrastructure.enums.RetryConfigEnum;
import com.ai.agent.infrastructure.utils.JsonUtil;
import com.ai.agent.starter.controller.LlmChatController;
import com.ai.agent.starter.facade.LlmFacadeImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import org.apache.dubbo.common.stream.StreamObserver;
import org.apache.dubbo.rpc.CancellationContext;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ResponsesTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType SSE = MediaType.parse("text/event-stream");
    private static final String ENDPOINT = "https://example.invalid/v1/responses";
    private static final String DEFAULT_ENDPOINT = "https://defaults.invalid/v1/responses";
    private static final String DELTA = "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}";
    private static final String COMPLETED = "{\"type\":\"response.completed\",\"response\":{\"id\":\"r1\"}}";

    enum Provider {
        OpenAI, Deepseek, Doubao, Qwen, Glm, Qianfan, Moonshot, MiniMax, Mimo, TokenHub, Ollama;

        boolean stateless() {
            return this == Moonshot || this == MiniMax || this == Mimo || this == TokenHub || this == Ollama;
        }

        boolean namedChoice() {
            return this == OpenAI || this == Deepseek || this == Doubao || this == Qwen
                    || this == TokenHub;
        }
    }

    static Map<String, Object> expectedNamedChoice(Provider provider) {
        Map<String, Object> function = Map.of("type", "function", "name", "lookup");
        return provider == Provider.Qwen ? Map.of("type", "allowed_tools", "mode", "required", "tools", List.of(function))
                : function;
    }

    static Stream<Provider> providers() {
        return Stream.of(Provider.values());
    }

    @ParameterizedTest
    @MethodSource("providers")
    void publicStreamSendsParametersAndFinishesOnlyOnSemanticTerminal(Provider provider) throws IOException {
        for (String terminalType : List.of("response.completed", "response.incomplete")) {
            Transport transport = new Transport(provider);
            String argumentsDone = "{\"type\":\"response.function_call_arguments.done\",\"arguments\":\"{}\"}";
            String textDone = "{\"type\":\"response.output_text.done\",\"text\":\"hello\"}";
            String terminal = "{\"type\":\"" + terminalType + "\",\"response\":{\"id\":\"r1\"}}";
            ResponseBody body = transport.respond(200, frame(DELTA) + frame(argumentsDone)
                    + frame(textDone) + frame(terminal) + "data:[DONE]\n\n");
            LlmResponsesRequest request = request(provider);

            List<String> chunks = transport.stream(request);

            assertEquals(5, chunks.size());
            assertJson(DELTA, chunks.get(0));
            assertJson(argumentsDone, chunks.get(1));
            assertJson(textDone, chunks.get(2));
            assertJson(terminal, chunks.get(3));
            assertNull(chunks.get(4));
            Request sent = transport.sentRequest(1);
            assertEquals(ENDPOINT, sent.url().toString());
            assertEquals("POST", sent.method());
            assertEquals("Bearer local-test-key", sent.header("Authorization"));
            JsonNode json = requestBody(sent);
            assertEquals("explicit-model", json.path("model").asText());
            assertEquals(MAPPER.valueToTree(request.getInput()), json.path("input"));
            assertEquals("instructions", json.path("instructions").asText());
            assertEquals(0.5, json.path("temperature").asDouble());
            assertEquals(0.8, json.path("top_p").asDouble());
            assertEquals(123, json.path("max_output_tokens").asInt());
            assertEquals(MAPPER.valueToTree(request.getTools()), json.path("tools"));
            assertEquals(provider.namedChoice() ? MAPPER.valueToTree(expectedNamedChoice(provider))
                    : MAPPER.valueToTree("auto"), json.path("tool_choice"));
            if (provider.stateless()) assertFalse(json.has("previous_response_id"));
            else assertEquals("previous", json.path("previous_response_id").asText());
            assertEquals(MAPPER.valueToTree(List.of("reasoning.encrypted_content")), json.path("include"));
            assertFalse(json.path("store").asBoolean(true));
            assertTrue(json.path("stream").asBoolean());
            assertFalse(json.has("apiKey"));
            assertFalse(json.has("endpoint"));
            assertEquals(false, request.getExtraParams().get("stream"));
            verifyNoInteractions(transport.nacos);
            verify(body, atLeastOnce()).close();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void failedAndErrorEventsAreForwardedWithoutSuccessOrRetry(Provider provider) throws IOException {
        for (String type : List.of("response.failed", "error")) {
            Transport transport = new Transport(provider);
            String failure = "{\"type\":\"" + type + "\",\"error\":{\"message\":\"failed\"}}";
            ResponseBody body = transport.respond(200, frame(DELTA) + frame(failure) + frame(COMPLETED));

            List<String> chunks = transport.stream(request(provider));

            assertEquals(2, chunks.size());
            assertJson(DELTA, chunks.get(0));
            assertJson(failure, chunks.get(1));
            assertFalse(chunks.contains(null));
            assertFalse(chunks.contains("[ERROR]"));
            transport.sentRequest(1);
            verify(body, atLeastOnce()).close();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void truncatedMalformedAndLegacyDoneStreamsErrorWithoutRetry(Provider provider) throws IOException {
        for (String tail : List.of("", "data:{broken}\n\n", "data:[DONE]\n\n",
                "data:" + COMPLETED + "\n")) {
            Transport transport = new Transport(provider);
            ResponseBody body = transport.respond(200, frame(DELTA) + tail);

            List<String> chunks = transport.stream(request(provider));

            assertEquals(2, chunks.size());
            assertJson(DELTA, chunks.get(0));
            assertEquals("[ERROR]", chunks.get(1));
            assertFalse(chunks.contains(null));
            transport.sentRequest(1);
            verify(body, atLeastOnce()).close();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void readFailureAfterFirstDeltaErrorsWithoutRetry(Provider provider) throws IOException {
        Transport transport = new Transport(provider);
        Buffer data = new Buffer().writeUtf8(frame(DELTA));
        ResponseBody body = spy(ResponseBody.create(SSE, -1, Okio.buffer(new ForwardingSource(data) {
            @Override
            public long read(Buffer sink, long byteCount) throws IOException {
                if (data.exhausted()) throw new IOException("local simulated read failure");
                return super.read(sink, byteCount);
            }
        })));
        when(transport.call.execute()).thenReturn(response(200, body));

        List<String> chunks = transport.stream(request(provider));

        assertEquals(2, chunks.size());
        assertJson(DELTA, chunks.get(0));
        assertEquals("[ERROR]", chunks.get(1));
        assertFalse(chunks.contains(null));
        transport.sentRequest(1);
        verify(body, atLeastOnce()).close();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void httpSendFailureCancelsCallClosesBodyAndNeverSendsAgain(Provider provider) throws IOException {
        JsonUtil.init(MAPPER);
        Transport transport = new Transport(provider);
        ResponseBody body = transport.respond(200, frame(DELTA) + frame(DELTA) + frame(COMPLETED));
        SseEmitter emitter = mock(SseEmitter.class);
        IOException disconnected = new IOException("disconnected");
        doThrow(disconnected).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        Consumer<String> downstream = ReflectionTestUtils.invokeMethod(new LlmChatController(mock(LlmRouter.class)),
                "buildResponsesSseConsumer", emitter, provider.name(), new AtomicBoolean());
        List<String> chunks = new ArrayList<>();
        transport.service.responsesStream(request(provider), chunk -> {
            chunks.add(chunk);
            if (DELTA.equals(chunk)) {
                UncheckedIOException cancelled = assertThrows(UncheckedIOException.class, () -> downstream.accept(chunk));
                assertSame(disconnected, cancelled.getCause());
                throw cancelled;
            }
            downstream.accept(chunk);
        });
        assertEquals(List.of(DELTA), chunks);
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).completeWithError(disconnected);
        verifyNoMoreInteractions(emitter);
        verify(body, atLeastOnce()).close();
        verify(transport.call).cancel();
        transport.sentRequest(1);
    }

    @ParameterizedTest
    @MethodSource("providers")
    @SuppressWarnings("unchecked")
    void dubboOnNextFailureCancelsTransportAndReportsErrorOnlyOnce(Provider provider) throws IOException {
        Transport transport = new Transport(provider);
        ResponseBody body = transport.respond(200, frame(DELTA) + frame(DELTA) + frame(COMPLETED));
        StreamObserver<String> observer = mock(StreamObserver.class);
        IllegalStateException disconnected = new IllegalStateException("disconnected");
        doThrow(disconnected).when(observer).onNext(DELTA);
        facade(transport).responsesStream(facadeRequest(provider), observer);
        verify(observer).onNext(DELTA);
        verify(observer).onError(disconnected);
        verifyNoMoreInteractions(observer);
        verify(body, atLeastOnce()).close();
        verify(transport.call).cancel();
        transport.sentRequest(1);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void non2xxClosesEveryAttemptAndNeverEmitsSuccessfulData(Provider provider) throws IOException {
        Transport transport = new Transport(provider);
        ResponseBody first = spy(ResponseBody.create(frame(COMPLETED), SSE));
        ResponseBody second = spy(ResponseBody.create(frame(COMPLETED), SSE));
        when(transport.call.execute()).thenReturn(response(503, first), response(503, second));

        assertEquals(List.of("[ERROR]"), transport.stream(request(provider)));

        transport.sentRequest(2);
        verify(first, atLeastOnce()).close();
        verify(second, atLeastOnce()).close();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void connectionFailureRetriesBeforeStreamStarts(Provider provider) throws IOException {
        Transport transport = new Transport(provider);
        ResponseBody body = spy(ResponseBody.create(frame(COMPLETED), SSE));
        when(transport.call.execute()).thenThrow(new IOException("local simulated connection failure"))
                .thenReturn(response(200, body));

        List<String> chunks = transport.stream(request(provider));

        assertEquals(2, chunks.size());
        assertJson(COMPLETED, chunks.get(0));
        assertNull(chunks.get(1));
        transport.sentRequest(2);
        verify(body, atLeastOnce()).close();
    }

    @ParameterizedTest
    @MethodSource("providers")
    void rejectedExecutorEmitsOnlyErrorWithoutOpeningConnection(Provider provider) {
        Transport transport = new Transport(provider);
        doThrow(new RejectedExecutionException("local simulated rejection"))
                .when(transport.executor).submit(any(Runnable.class));

        assertEquals(List.of("[ERROR]"), transport.stream(request(provider)));

        verify(transport.executor).submit(any(Runnable.class));
        verifyNoInteractions(transport.http, transport.client, transport.call, transport.retryConfig, transport.nacos);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void defaultsComeFromResponsesObjectAndPreserveExplicitFields(Provider provider) throws IOException {
        for (String missing : List.of("all", "apiKey", "endpoint", "model")) {
            Transport transport = new Transport(provider);
            transport.stubResponsesDefaults();
            ResponseBody body = transport.respond(200, frame(COMPLETED));
            LlmResponsesRequest request = request(provider);
            if ("all".equals(missing) || "apiKey".equals(missing)) request.setApiKey(" ");
            if ("all".equals(missing) || "endpoint".equals(missing)) request.setEndpoint(null);
            if ("all".equals(missing) || "model".equals(missing)) request.setModel("");

            List<String> chunks = transport.stream(request);

            assertEquals(2, chunks.size());
            assertJson(COMPLETED, chunks.get(0));
            assertNull(chunks.get(1));
            Request sent = transport.sentRequest(1);
            String expectedKey = "all".equals(missing) || "apiKey".equals(missing) ? "local-default-key" : "local-test-key";
            String expectedEndpoint = "all".equals(missing) || "endpoint".equals(missing) ? DEFAULT_ENDPOINT : ENDPOINT;
            String expectedModel = "all".equals(missing) || "model".equals(missing) ? "responses-default-model" : "explicit-model";
            assertEquals("Bearer " + expectedKey, sent.header("Authorization"));
            assertEquals(expectedEndpoint, sent.url().toString());
            assertEquals(expectedModel, requestBody(sent).path("model").asText());
            assertTrue(requestBody(sent).path("stream").asBoolean());
            transport.verifyOnlyResponsesDefaults();
            verify(body, atLeastOnce()).close();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void queuedFutureCancellationNeverOpensConnection(Provider provider) {
        Transport transport = new Transport(provider);
        AtomicReference<FutureTask<Void>> queued = new AtomicReference<>();
        doAnswer(invocation -> {
            FutureTask<Void> task = new FutureTask<>(invocation.getArgument(0, Runnable.class), null);
            queued.set(task);
            return task;
        }).when(transport.executor).submit(any(Runnable.class));
        List<String> chunks = new ArrayList<>();
        CancellableConsumer consumer = ResponsesStreamUtil.cancellable(chunks::add);

        transport.service.responsesStream(request(provider), consumer);
        consumer.cancel();
        assertTrue(queued.get().isCancelled());
        queued.get().run();

        assertTrue(chunks.isEmpty());
        verifyNoInteractions(transport.http, transport.client, transport.call, transport.retryConfig);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void cancellationBeforeCallCreationNeverOpensConnection(Provider provider) {
        Transport transport = new Transport(provider);
        List<String> chunks = new ArrayList<>();
        CancellableConsumer consumer = ResponsesStreamUtil.cancellable(chunks::add);
        RetryParam retry = new RetryParam();
        retry.setMaxRetries(1);
        when(transport.retryConfig.getRetryParam(RetryConfigEnum.valueOf(provider.name()))).thenAnswer(invocation -> {
            consumer.cancel();
            return retry;
        });

        transport.service.responsesStream(request(provider), consumer);

        assertTrue(consumer.isCancelled());
        assertTrue(chunks.isEmpty());
        verify(transport.retryConfig).getRetryParam(RetryConfigEnum.valueOf(provider.name()));
        verifyNoMoreInteractions(transport.retryConfig);
        verifyNoInteractions(transport.http, transport.client, transport.call);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void cancellationDuringCallCreationCancelsLateCallWithoutExecutingOrRetrying(Provider provider) throws Exception {
        Transport transport = new Transport(provider);
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch returnCall = new CountDownLatch(1);
        List<String> chunks = new ArrayList<>();
        CancellableConsumer consumer = ResponsesStreamUtil.cancellable(chunks::add);
        when(transport.client.newCall(any(Request.class))).thenAnswer(invocation -> {
            creating.countDown();
            awaitIgnoringInterrupt(returnCall);
            return transport.call;
        });
        try (AsyncTransport async = new AsyncTransport(transport)) {
            try {
                transport.service.responsesStream(request(provider), consumer);
                assertTrue(creating.await(5, TimeUnit.SECONDS));
                consumer.cancel();
                returnCall.countDown();
                async.awaitFinished();
                assertTrue(async.task.get().isCancelled());
                assertTrue(chunks.isEmpty());
                verify(transport.client).newCall(any(Request.class));
                verify(transport.call).cancel();
                verifyNoMoreInteractions(transport.client, transport.call);
            } finally {
                consumer.cancel();
                returnCall.countDown();
            }
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void cancellationDuringExecuteClosesLateResponseWithoutDispatchOrRetry(Provider provider) throws Exception {
        Transport transport = new Transport(provider);
        ResponseBody body = spy(ResponseBody.create(frame(COMPLETED), SSE));
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch returnResponse = new CountDownLatch(1);
        List<String> chunks = new ArrayList<>();
        CancellableConsumer consumer = ResponsesStreamUtil.cancellable(chunks::add);
        when(transport.call.execute()).thenAnswer(invocation -> {
            executing.countDown();
            awaitIgnoringInterrupt(returnResponse);
            return response(200, body);
        });
        try (AsyncTransport async = new AsyncTransport(transport)) {
            try {
                transport.service.responsesStream(request(provider), consumer);
                assertTrue(executing.await(5, TimeUnit.SECONDS));
                consumer.cancel();
                returnResponse.countDown();
                async.awaitFinished();
                assertTrue(async.task.get().isCancelled());
                assertTrue(chunks.isEmpty());
                verify(transport.call).cancel();
                verify(body, atLeastOnce()).close();
                transport.sentRequest(1);
            } finally {
                consumer.cancel();
                returnResponse.countDown();
            }
        } finally {
            body.close();
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void executeFailureCausedByCancellationNeverRetriesOrDispatchesError(Provider provider) throws IOException {
        Transport transport = new Transport(provider);
        List<String> chunks = new ArrayList<>();
        CancellableConsumer consumer = ResponsesStreamUtil.cancellable(chunks::add);
        when(transport.call.execute()).thenAnswer(invocation -> {
            consumer.cancel();
            throw new IOException("cancelled execute");
        });

        transport.service.responsesStream(request(provider), consumer);

        assertTrue(chunks.isEmpty());
        verify(transport.call).cancel();
        transport.sentRequest(1);
    }

    @ParameterizedTest
    @MethodSource("providers")
    void cancellationInterruptsRetryWaitWithoutSecondAttempt(Provider provider) throws Exception {
        Transport transport = new Transport(provider);
        RetryParam retry = new RetryParam();
        retry.setMaxRetries(1);
        retry.setIntervalMs(30000);
        retry.setMaxWaitMs(30000);
        retry.setBackoffMultiplier(1);
        when(transport.retryConfig.getRetryParam(RetryConfigEnum.valueOf(provider.name()))).thenReturn(retry);
        CountDownLatch failed = new CountDownLatch(1);
        when(transport.call.execute()).thenAnswer(invocation -> {
            failed.countDown();
            throw new IOException("local simulated connection failure");
        });
        List<String> chunks = new ArrayList<>();
        CancellableConsumer consumer = ResponsesStreamUtil.cancellable(chunks::add);
        try (AsyncTransport async = new AsyncTransport(transport)) {
            transport.service.responsesStream(request(provider), consumer);
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (async.thread.get().getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(Thread.State.TIMED_WAITING, async.thread.get().getState());
            consumer.cancel();
            async.awaitFinished();
            assertTrue(async.task.get().isCancelled());
            assertTrue(chunks.isEmpty());
            verify(transport.call).cancel();
            transport.sentRequest(1);
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    @SuppressWarnings("unchecked")
    void realBlockedResponseReadIsCancelledDirectlyAndThroughRpcContext(Provider provider) throws Exception {
        for (boolean rpc : List.of(false, true)) {
            Transport transport = new Transport(provider);
            CountDownLatch holdResponse = new CountDownLatch(1);
            CountDownLatch reading = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicBoolean closed = new AtomicBoolean();
            AtomicInteger requests = new AtomicInteger();
            AtomicReference<Call> realCall = new AtomicReference<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
            OkHttpClient realClient = new OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build();
            CancellationContext previous = RpcContext.getCancellationContext();
            CancellationContext cancellation = new CancellationContext();
            server.setExecutor(serverExecutor);
            server.createContext("/v1/responses", exchange -> {
                requests.incrementAndGet();
                try {
                    exchange.getRequestBody().readAllBytes();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(frame(DELTA).getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    awaitIgnoringInterrupt(holdResponse);
                } finally {
                    exchange.close();
                }
            });
            try {
                server.start();
                String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses";
                when(transport.client.newCall(any(Request.class))).thenAnswer(invocation -> {
                    Call call = spy(realClient.newCall(invocation.getArgument(0, Request.class)));
                    realCall.set(call);
                    doAnswer(execute -> {
                        Response response = (Response) execute.callRealMethod();
                        ResponseBody original = response.body();
                        ResponseBody tracked = ResponseBody.create(SSE, -1, Okio.buffer(new ForwardingSource(original.source()) {
                            private int reads;

                            @Override
                            public long read(Buffer sink, long byteCount) throws IOException {
                                if (++reads > 1) reading.countDown();
                                return super.read(sink, byteCount);
                            }

                            @Override
                            public void close() throws IOException {
                                closed.set(true);
                                super.close();
                            }
                        }));
                        return response.newBuilder().body(tracked).build();
                    }).when(call).execute();
                    return call;
                });
                List<String> chunks = new ArrayList<>();
                CancellableConsumer consumer = ResponsesStreamUtil.cancellable(chunk -> {
                    chunks.add(chunk);
                    delivered.countDown();
                });
                StreamObserver<String> observer = mock(StreamObserver.class);
                doAnswer(invocation -> {
                    chunks.add(invocation.getArgument(0, String.class));
                    delivered.countDown();
                    return null;
                }).when(observer).onNext(any());
                RpcContext.restoreCancellationContext(cancellation);
                try (AsyncTransport async = new AsyncTransport(transport)) {
                    try {
                        if (rpc) {
                            LlmFacadeResponsesRequest request = facadeRequest(provider);
                            request.setEndpoint(endpoint);
                            facade(transport).responsesStream(request, observer);
                        } else {
                            LlmResponsesRequest request = request(provider);
                            request.setEndpoint(endpoint);
                            transport.service.responsesStream(request, consumer);
                        }
                        assertTrue(delivered.await(5, TimeUnit.SECONDS));
                        assertTrue(reading.await(5, TimeUnit.SECONDS));
                        if (rpc) assertTrue(cancellation.cancel(new IOException("client cancelled")));
                        else consumer.cancel();
                        async.awaitFinished();
                        assertTrue(async.task.get().isCancelled());
                        verify(realCall.get()).cancel();
                        assertTrue(realCall.get().isCanceled());
                        assertTrue(closed.get());
                        assertEquals(List.of(DELTA), chunks);
                        assertEquals(1, requests.get());
                        verify(transport.client).newCall(any(Request.class));
                        verifyNoMoreInteractions(transport.client);
                        if (rpc) {
                            verify(observer).onNext(DELTA);
                            verifyNoMoreInteractions(observer);
                        }
                    } finally {
                        consumer.cancel();
                        cancellation.cancel(new IOException("test cleanup"));
                        if (realCall.get() != null) realCall.get().cancel();
                        holdResponse.countDown();
                    }
                }
            } finally {
                RpcContext.removeCancellationContext();
                RpcContext.restoreCancellationContext(previous);
                cancellation.close();
                holdResponse.countDown();
                if (realCall.get() != null) realCall.get().cancel();
                server.stop(0);
                serverExecutor.shutdownNow();
                realClient.dispatcher().executorService().shutdownNow();
                realClient.connectionPool().evictAll();
                assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(realClient.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("providers")
    void syncCallPreservesReasoningAndFunctionHistoryAndUsageDetails(Provider provider) throws IOException {
        Transport transport = new Transport(provider);
        String json = """
                {"id":"response-1","created_at":123,"status":"completed","max_output_tokens":123,
                 "output":[{"type":"reasoning","id":"reason-1","encrypted_content":"opaque",
                            "summary":[{"type":"summary_text","text":"history"}]},
                           {"type":"function_call","call_id":"call-1","name":"lookup","arguments":"{}"},
                           {"type":"message","role":"assistant","content":[{"type":"output_text","text":"answer"}]}],
                 "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,
                          "input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":3}}}
                """;
        ResponseBody body = transport.respond(200, json);
        LlmResponsesRequest request = request(provider);
        List<LlmResponsesInput> input = List.of(
                LlmResponsesInput.builder().type("reasoning").id("reason-1").encryptedContent("opaque")
                        .summary(List.of(Map.of("type", "summary_text", "text", "history"))).build(),
                LlmResponsesInput.builder().type("function_call").callId("call-1").name("lookup").arguments("{}").build(),
                LlmResponsesInput.builder().type("function_call_output").callId("call-1").output("result").build());
        request.setInput(input);
        JsonNode original = MAPPER.valueToTree(request);

        LlmResponse result = transport.service.responses(request);

        assertNotNull(result);
        assertEquals("response-1", result.getRequestId());
        assertEquals("explicit-model", result.getModelCode());
        assertEquals(123L, result.getCreatedAt());
        assertEquals("completed", result.getStatus());
        assertEquals(123, result.getMaxOutputTokens());
        assertEquals(3, result.getOutput().size());
        assertEquals("opaque", result.getOutput().get(0).getEncryptedContent());
        assertEquals("history", result.getOutput().get(0).getSummary().get(0).getText());
        assertEquals("call-1", result.getOutput().get(1).getCallId());
        assertEquals("lookup", result.getOutput().get(1).getName());
        assertEquals("{}", result.getOutput().get(1).getArguments());
        assertEquals("answer", result.getOutput().get(2).getContent().get(0).getText());
        assertEquals(10, result.getUsage().getInputTokens());
        assertEquals(5, result.getUsage().getOutputTokens());
        assertEquals(15, result.getUsage().getTotalTokens());
        if (provider != Provider.OpenAI && provider != Provider.Deepseek && provider != Provider.Doubao) {
            assertEquals(4, result.getUsage().getInputTokensDetails().getCachedTokens());
            assertEquals(3, result.getUsage().getOutputTokensDetails().getReasoningTokens());
        }
        Request sent = transport.sentRequest(1, false);
        assertEquals("Bearer local-test-key", sent.header("Authorization"));
        assertEquals(MAPPER.valueToTree(input), requestBody(sent).path("input"));
        assertFalse(requestBody(sent).path("stream").asBoolean(true));
        assertSame(input, request.getInput());
        assertEquals(original, MAPPER.valueToTree(request));
        verifyNoInteractions(transport.nacos);
        verify(body, atLeastOnce()).close();
    }

    static Stream<Provider> newProviders() {
        return providers().filter(provider -> provider != Provider.OpenAI
                && provider != Provider.Deepseek && provider != Provider.Doubao);
    }

    static Stream<Provider> statelessProviders() {
        return providers().filter(Provider::stateless);
    }

    @ParameterizedTest
    @MethodSource("statelessProviders")
    void statelessParametersAreRejectedSynchronouslyBeforeSyncOrStreamHttp(Provider provider) {
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of("previous_response_id", "previous"),
                Map.<String, Object>of("previous_response_id", ""), Map.<String, Object>of("conversation", "conversation-1"),
                Map.<String, Object>of("conversation", Map.of("id", "conversation-1")),
                Map.<String, Object>of("store", true), Map.<String, Object>of("background", true))) {
            Transport transport = new Transport(provider);
            LlmResponsesRequest request = request(provider);
            request.setExtraParams(invalid);
            assertRejectedWithoutHttp(transport, request, ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    @ParameterizedTest
    @MethodSource("newProviders")
    void nonFunctionToolsAreRejectedInCommonAndExtraParameters(Provider provider) {
        for (String type : List.of("web_search", "file_search", "code_interpreter")) {
            for (boolean extra : List.of(false, true)) {
                Transport transport = new Transport(provider);
                LlmResponsesRequest request = request(provider);
                List<Map<String, Object>> tools = List.of(Map.of("type", type));
                request.setTools(extra ? null : tools);
                request.setToolChoice("auto");
                if (extra) request.setExtraParams(Map.of("tools", tools));
                assertRejectedWithoutHttp(transport, request, ErrorCodeEnum.PARAM_ILLEGAL);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("newProviders")
    void unsupportedInputTypesAreRejectedBeforeHttp(Provider provider) {
        Transport transport = new Transport(provider);
        LlmResponsesRequest request = request(provider);
        request.setInput(List.of(LlmResponsesInput.builder().type("web_search_call").build()));
        assertRejectedWithoutHttp(transport, request, ErrorCodeEnum.PARAM_ILLEGAL);
        request.setInput(List.of(LlmResponsesInput.builder().role("user")
                .content(List.of(LlmResponsesContent.builder().type("input_image").imageUrl("https://example.invalid/image.png").build())).build()));
        assertRejectedWithoutHttp(transport, request, ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
        request.setInput(List.of(LlmResponsesInput.builder().role("user")
                .content(List.of(LlmResponsesContent.builder().text("hello").build())).build()));
        assertRejectedWithoutHttp(transport, request, ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
    }

    @ParameterizedTest
    @MethodSource("newProviders")
    void unsupportedToolChoicesAreRejectedInCommonAndExtraParameters(Provider provider) {
        if (provider.namedChoice()) return;
        List<String> unsupported = provider == Provider.Glm ? List.of("required", "lookup")
                : List.of("none", "required", "lookup");
        List<Object> choices = new ArrayList<>(unsupported);
        choices.add(Map.of("type", "function", "name", "lookup"));
        for (Object choice : choices) {
            for (boolean extra : List.of(false, true)) {
                if (!extra && !(choice instanceof String)) continue;
                Transport transport = new Transport(provider);
                LlmResponsesRequest request = request(provider);
                request.setToolChoice(extra ? null : (String) choice);
                if (extra) request.setExtraParams(Map.of("tool_choice", choice));
                assertRejectedWithoutHttp(transport, request, ErrorCodeEnum.PARAM_ILLEGAL);
            }
        }
    }

    @Test
    void qwenBackgroundAndRequiredWithMultipleToolsAreRejectedBeforeHttp() {
        Transport background = new Transport(Provider.Qwen);
        LlmResponsesRequest request = request(Provider.Qwen);
        request.setExtraParams(Map.of("background", true));
        JsonNode original = MAPPER.valueToTree(request);
        assertRejectedWithoutHttp(background, request, ErrorCodeEnum.PARAM_ILLEGAL);
        assertEquals(original, MAPPER.valueToTree(request));
        for (boolean extraChoice : List.of(false, true)) {
            for (boolean extraTools : List.of(false, true)) {
                Transport transport = new Transport(Provider.Qwen);
                LlmResponsesRequest required = request(Provider.Qwen);
                List<Map<String, Object>> tools = List.of(required.getTools().get(0),
                        Map.of("type", "function", "name", "second_lookup", "parameters", Map.of("type", "object")));
                Map<String, Object> extra = new LinkedHashMap<>();
                required.setToolChoice(extraChoice ? null : "required");
                if (extraChoice) extra.put("tool_choice", "required");
                required.setTools(extraTools ? null : tools);
                if (extraTools) extra.put("tools", tools);
                required.setExtraParams(extra);
                JsonNode before = MAPPER.valueToTree(required);
                assertRejectedWithoutHttp(transport, required, ErrorCodeEnum.PARAM_ILLEGAL);
                assertSame(extra, required.getExtraParams());
                assertEquals(before, MAPPER.valueToTree(required));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("newProviders")
    void nullOptionalParametersAndFalseStateFlagsAreAllowedWithoutMutatingRequest(Provider provider) throws IOException {
        for (boolean stream : List.of(false, true)) {
            Transport transport = new Transport(provider);
            transport.respond(200, stream ? frame(COMPLETED)
                    : "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[]}");
            LlmResponsesRequest request = request(provider);
            request.setTools(null);
            request.setToolChoice(null);
            request.setInstructions(null);
            request.setTemperature(null);
            request.setTopP(null);
            request.setMaxOutputTokens(null);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("previous_response_id", null);
            extra.put("conversation", null);
            extra.put("store", false);
            extra.put("background", false);
            request.setExtraParams(extra);
            JsonNode original = MAPPER.valueToTree(request);
            if (stream) {
                List<String> chunks = transport.stream(request);
                assertEquals(2, chunks.size());
                assertNull(chunks.get(1));
            } else assertNotNull(transport.service.responses(request));
            JsonNode body = requestBody(transport.sentRequest(1, stream));
            assertEquals(stream, body.path("stream").asBoolean());
            assertFalse(body.path("store").asBoolean(true));
            assertFalse(body.path("background").asBoolean(true));
            for (String key : List.of("instructions", "temperature", "top_p", "max_output_tokens", "tools")) {
                assertFalse(body.has(key), key);
            }
            assertTrue(!body.has("tool_choice") || "auto".equals(body.path("tool_choice").asText()));
            assertSame(extra, request.getExtraParams());
            assertEquals(original, MAPPER.valueToTree(request));
        }
    }

    @ParameterizedTest
    @MethodSource("newProviders")
    void syncErrorPayloadsNeverBecomeEmptySuccessfulResponses(Provider provider) throws IOException {
        for (String payload : List.of("", "{}", "null", "{broken}",
                "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":{}}]}",
                "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{}]}]}",
                "{\"error\":{\"message\":\"failed\"}}",
                "{\"id\":\"r1\",\"status\":\"failed\",\"output\":[],\"error\":{\"message\":\"failed\"}}")) {
            Transport transport = new Transport(provider);
            transport.respond(200, payload);
            assertThrows(BizException.class, () -> transport.service.responses(request(provider)), payload);
            verifyNoInteractions(transport.executor);
        }
    }

    @ParameterizedTest
    @MethodSource("newProviders")
    void syncNon2xxNeverReturnsSuccessAndClosesEveryAttempt(Provider provider) throws IOException {
        Transport transport = new Transport(provider);
        ResponseBody first = spy(ResponseBody.create("{\"error\":{\"message\":\"unavailable\"}}", SSE));
        ResponseBody second = spy(ResponseBody.create("{\"error\":{\"message\":\"unavailable\"}}", SSE));
        when(transport.call.execute()).thenReturn(response(503, first), response(503, second));
        assertThrows(BizException.class, () -> transport.service.responses(request(provider)));
        transport.sentRequest(2, false);
        verify(first, atLeastOnce()).close();
        verify(second, atLeastOnce()).close();
    }

    @Test
    void glmOpaqueKeyIsSentDirectlyAsBearerForSyncAndStream() throws IOException {
        for (boolean stream : List.of(false, true)) {
            Transport transport = new Transport(Provider.Glm);
            transport.respond(200, stream ? frame(COMPLETED)
                    : "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[]}");
            LlmResponsesRequest request = request(Provider.Glm);
            request.setApiKey("opaque-not-a-jwt.key");
            if (stream) assertNull(transport.stream(request).get(1));
            else assertNotNull(transport.service.responses(request));
            assertEquals("Bearer opaque-not-a-jwt.key", transport.sentRequest(1, stream).header("Authorization"));
        }
    }

    @Test
    void ollamaLocalNeedsNoKeyButCloudRejectsMissingKeyBeforeHttp() throws IOException {
        for (boolean stream : List.of(false, true)) {
            Transport transport = new Transport(Provider.Ollama);
            transport.respond(200, stream ? frame(COMPLETED)
                    : "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[]}");
            LlmResponsesRequest request = request(Provider.Ollama);
            request.setApiKey(null);
            request.setEndpoint("http://localhost:11434/v1/responses");
            if (stream) assertNull(transport.stream(request).get(1));
            else assertNotNull(transport.service.responses(request));
            assertEquals("Bearer ollama", transport.sentRequest(1, stream).header("Authorization"));
            transport.verifyOnlyResponsesDefaults();
        }
        Transport cloud = new Transport(Provider.Ollama);
        LlmResponsesRequest request = request(Provider.Ollama);
        request.setApiKey(null);
        request.setEndpoint("https://ollama.com/v1/responses");
        assertRejectedWithoutHttp(cloud, request, ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
    }

    private static void assertRejectedWithoutHttp(Transport transport, LlmResponsesRequest request, ErrorCodeEnum code) {
        assertEquals(code, assertThrows(BizException.class, () -> transport.service.responses(request)).getErrorCode());
        List<String> chunks = new ArrayList<>();
        assertEquals(code, assertThrows(BizException.class,
                () -> transport.service.responsesStream(request, chunks::add)).getErrorCode());
        assertTrue(chunks.isEmpty());
        verifyNoInteractions(transport.executor, transport.http, transport.client, transport.call, transport.retryConfig);
    }

    private static LlmFacadeResponsesRequest facadeRequest(Provider provider) {
        LlmFacadeResponsesInput input = new LlmFacadeResponsesInput();
        input.setType("message");
        input.setRole("user");
        LlmFacadeResponsesContent content = new LlmFacadeResponsesContent();
        content.setType("input_text");
        content.setText("hello");
        input.setContent(List.of(content));
        LlmFacadeResponsesRequest request = new LlmFacadeResponsesRequest();
        request.setPlatform(provider.name());
        request.setModel("model");
        request.setApiKey("key");
        request.setEndpoint(ENDPOINT);
        request.setInput(List.of(input));
        request.setTools(List.of(Map.of("type", "function", "name", "lookup", "parameters", Map.of("type", "object"))));
        request.setToolChoice(provider.namedChoice() ? "lookup" : "auto");
        Map<String, Object> extra = new LinkedHashMap<>(Map.of("store", false));
        if (!provider.stateless()) extra.put("previous_response_id", "previous");
        request.setExtraParams(extra);
        return request;
    }

    private static LlmFacadeImpl facade(Transport transport) {
        String name = transport.provider.name();
        String beanName = Character.toLowerCase(name.charAt(0)) + name.substring(1) + "ServiceImpl";
        return new LlmFacadeImpl(new LlmRouter(Map.of(beanName, transport.service)));
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (true) {
                try {
                    assertTrue(latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                    return;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static class AsyncTransport implements AutoCloseable {
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private final CountDownLatch finished = new CountDownLatch(1);
        private final AtomicReference<Thread> thread = new AtomicReference<>();
        private final AtomicReference<Future<?>> task = new AtomicReference<>();

        private AsyncTransport(Transport transport) {
            doAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(0, Runnable.class);
                Future<?> future = worker.submit(() -> {
                    thread.set(Thread.currentThread());
                    try {
                        runnable.run();
                    } finally {
                        finished.countDown();
                    }
                });
                task.set(future);
                return future;
            }).when(transport.executor).submit(any(Runnable.class));
        }

        private void awaitFinished() throws InterruptedException {
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }

        @Override
        public void close() throws InterruptedException {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static LlmResponsesRequest request(Provider provider) {
        Map<String, Object> extra = new LinkedHashMap<>(Map.of("stream", false, "store", false,
                "include", List.of("reasoning.encrypted_content")));
        if (!provider.stateless()) extra.put("previous_response_id", "previous");
        return LlmResponsesRequest.builder().model("explicit-model").apiKey("local-test-key").endpoint(ENDPOINT)
                .input(List.of(LlmResponsesInput.builder().type("message").role("user")
                        .content(List.of(LlmResponsesContent.builder().type("input_text").text("hello").build())).build()))
                .instructions("instructions").temperature(0.5).topP(0.8).maxOutputTokens(123)
                .tools(List.of(Map.of("type", "function", "name", "lookup", "parameters", Map.of("type", "object"))))
                .toolChoice(provider.namedChoice() ? "lookup" : "auto").extraParams(extra).build();
    }

    private static String frame(String json) {
        return "data:" + json + "\n\n";
    }

    private static Response response(int code, ResponseBody body) {
        return new Response.Builder().request(new Request.Builder().url(ENDPOINT).build())
                .protocol(Protocol.HTTP_1_1).code(code).message("local mock response").body(body).build();
    }

    private static JsonNode requestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        assertNotNull(request.body());
        request.body().writeTo(buffer);
        return MAPPER.readTree(buffer.readUtf8());
    }

    private static void assertJson(String expected, String actual) throws IOException {
        assertNotNull(actual);
        assertEquals(MAPPER.readTree(expected), MAPPER.readTree(actual));
    }

    private static class Transport {
        private final Provider provider;
        private final ExecutorService executor = mock(ExecutorService.class);
        private final OkHttpConfig http = mock(OkHttpConfig.class);
        private final OkHttpClient client = mock(OkHttpClient.class);
        private final Call call = mock(Call.class);
        private final RetryConfig retryConfig = mock(RetryConfig.class);
        private final NacosConfig nacos = mock(NacosConfig.class);
        private final LlmService service;

        private Transport(Provider provider) {
            this.provider = provider;
            RetryParam retry = new RetryParam();
            retry.setMaxRetries(1);
            retry.setIntervalMs(0);
            retry.setMaxWaitMs(0);
            when(http.getClientByPlatform(OkHttpConfigEnum.valueOf(provider.name()))).thenReturn(client);
            when(client.newCall(any(Request.class))).thenReturn(call);
            when(retryConfig.getRetryParam(RetryConfigEnum.valueOf(provider.name()))).thenReturn(retry);
            doAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return CompletableFuture.completedFuture(null);
            }).when(executor).submit(any(Runnable.class));
            service = switch (provider) {
                case OpenAI -> new OpenAIServiceImpl(executor, http, retryConfig, nacos);
                case Deepseek -> new DeepseekServiceImpl(executor, http, retryConfig, nacos);
                case Doubao -> new DoubaoServiceImpl(executor, http, retryConfig, nacos);
                case Qwen -> new QwenServiceImpl(executor, http, retryConfig, nacos);
                case Glm -> new GlmServiceImpl(executor, http, retryConfig, nacos);
                case Qianfan -> new QianfanServiceImpl(executor, http, retryConfig, nacos);
                case Moonshot -> new MoonshotServiceImpl(executor, http, retryConfig, nacos);
                case MiniMax -> new MiniMaxServiceImpl(executor, http, retryConfig, nacos);
                case Mimo -> new MimoServiceImpl(executor, http, retryConfig, nacos);
                case TokenHub -> new TokenHubServiceImpl(executor, http, retryConfig, nacos);
                case Ollama -> new OllamaServiceImpl(executor, http, retryConfig, nacos);
            };
        }

        private ResponseBody respond(int code, String sse) throws IOException {
            ResponseBody body = spy(ResponseBody.create(sse, SSE));
            when(call.execute()).thenReturn(response(code, body));
            return body;
        }

        private List<String> stream(LlmResponsesRequest request) {
            List<String> chunks = new ArrayList<>();
            service.responsesStream(request, chunks::add);
            return chunks;
        }

        private Request sentRequest(int attempts) throws IOException {
            return sentRequest(attempts, true);
        }

        private Request sentRequest(int attempts, boolean stream) throws IOException {
            if (stream) verify(executor).submit(any(Runnable.class));
            else verifyNoInteractions(executor);
            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            int clientLookups = stream || provider == Provider.OpenAI || provider == Provider.Deepseek || provider == Provider.Doubao ? attempts : 1;
            verify(http, times(clientLookups)).getClientByPlatform(OkHttpConfigEnum.valueOf(provider.name()));
            verify(retryConfig).getRetryParam(RetryConfigEnum.valueOf(provider.name()));
            verify(client, times(attempts)).newCall(captor.capture());
            verify(call, times(attempts)).execute();
            verifyNoMoreInteractions(http, retryConfig, client, call);
            return captor.getValue();
        }

        private void stubResponsesDefaults() {
            switch (provider) {
                case OpenAI -> {
                    OpenAIBO config = new OpenAIBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_OPENAI, "responses", OpenAIBO.class)).thenReturn(config);
                }
                case Deepseek -> {
                    DeepseekBO config = new DeepseekBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_DEEPSEEK, "responses", DeepseekBO.class)).thenReturn(config);
                }
                case Doubao -> {
                    DoubaoBO config = new DoubaoBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModel("responses-default-model");
                    config.setEndpointId("must-not-use-chat-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_DOUBAO, "responses", DoubaoBO.class)).thenReturn(config);
                }
                case Qwen -> {
                    QwenBO config = new QwenBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_QWEN, "responses", QwenBO.class)).thenReturn(config);
                }
                case Glm -> {
                    GlmBO config = new GlmBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_GLM, "responses", GlmBO.class)).thenReturn(config);
                }
                case Qianfan -> {
                    QianfanBO config = new QianfanBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_QIANFAN, "responses", QianfanBO.class)).thenReturn(config);
                }
                case Moonshot -> {
                    MoonshotBO config = new MoonshotBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_MOONSHOT, "responses", MoonshotBO.class)).thenReturn(config);
                }
                case MiniMax -> {
                    MiniMaxBO config = new MiniMaxBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_MINIMAX, "responses", MiniMaxBO.class)).thenReturn(config);
                }
                case Mimo -> {
                    MimoBO config = new MimoBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_MIMO, "responses", MimoBO.class)).thenReturn(config);
                }
                case TokenHub -> {
                    TokenHubBO config = new TokenHubBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_TOKENHUB, "responses", TokenHubBO.class)).thenReturn(config);
                }
                case Ollama -> {
                    OllamaBO config = new OllamaBO();
                    config.setApiKey("local-default-key");
                    config.setEndpoint(DEFAULT_ENDPOINT);
                    config.setModelCode("responses-default-model");
                    when(nacos.getObject(NacosDataIdEnum.AI_AGENT_OLLAMA, "responses", OllamaBO.class)).thenReturn(config);
                }
            }
        }

        private void verifyOnlyResponsesDefaults() {
            switch (provider) {
                case OpenAI -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_OPENAI, "responses", OpenAIBO.class);
                case Deepseek -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_DEEPSEEK, "responses", DeepseekBO.class);
                case Doubao -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_DOUBAO, "responses", DoubaoBO.class);
                case Qwen -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_QWEN, "responses", QwenBO.class);
                case Glm -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_GLM, "responses", GlmBO.class);
                case Qianfan -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_QIANFAN, "responses", QianfanBO.class);
                case Moonshot -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_MOONSHOT, "responses", MoonshotBO.class);
                case MiniMax -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_MINIMAX, "responses", MiniMaxBO.class);
                case Mimo -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_MIMO, "responses", MimoBO.class);
                case TokenHub -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_TOKENHUB, "responses", TokenHubBO.class);
                case Ollama -> verify(nacos).getObject(NacosDataIdEnum.AI_AGENT_OLLAMA, "responses", OllamaBO.class);
            }
            verifyNoMoreInteractions(nacos);
        }
    }
}

