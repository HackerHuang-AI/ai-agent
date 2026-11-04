package com.ai.agent.application.utils;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResponsesStreamUtilTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void semanticDoneEventsDoNotFinishResponse() {
        List<String> chunks = new ArrayList<>();
        String argumentsDone = "{\"type\":\"response.function_call_arguments.done\",\"arguments\":\"{}\"}";
        String textDone = "{\"type\":\"response.output_text.done\",\"text\":\"hello\"}";
        String completed = "{\"type\":\"response.completed\",\"response\":{\"usage\":{\"total_tokens\":3}}}";
        parse(frame(argumentsDone) + frame(textDone) + frame(completed) + "data:[DONE]\n\n", chunks);
        assertEquals(Arrays.asList(argumentsDone, textDone, completed, null), chunks);
    }

    @Test
    void incompleteIsNormalTermination() {
        List<String> chunks = new ArrayList<>();
        String incomplete = "{\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}";
        parse(frame(incomplete), chunks);
        assertEquals(Arrays.asList(incomplete, null), chunks);
    }

    @Test
    void failedIsForwardedWithoutSuccessSignal() {
        assertFailureEvent("response.failed");
    }

    @Test
    void errorIsForwardedWithoutSuccessSignal() {
        assertFailureEvent("error");
    }

    @Test
    void unexpectedEofFailsAfterForwardingDelta() {
        List<String> chunks = new ArrayList<>();
        String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}";
        BizException error = assertThrows(BizException.class, () -> parse(frame(delta), chunks));
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, error.getErrorCode());
        assertEquals(List.of(delta), chunks);
    }

    @Test
    void doneWithoutResponsesTerminalFails() {
        List<String> chunks = new ArrayList<>();
        String done = "{\"type\":\"response.output_text.done\",\"text\":\"hi\"}";
        BizException error = assertThrows(BizException.class, () -> parse(frame(done) + "data:[DONE]\n\n", chunks));
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, error.getErrorCode());
        assertEquals(List.of(done), chunks);
    }

    @Test
    void unframedTerminalAtEofDoesNotSilentlySucceed() {
        List<String> chunks = new ArrayList<>();
        assertThrows(BizException.class, () -> parse("data:{\"type\":\"response.completed\"}\n", chunks));
        assertTrue(chunks.isEmpty());
    }

    @Test
    void noSpaceDataCommentsAndEventTypeFallback() throws IOException {
        List<String> chunks = new ArrayList<>();
        parse(":heartbeat\r\nevent:response.completed\r\nid:1\r\ndata:{\"response\":{\"id\":\"r1\"}}\r\n\r\n", chunks);
        assertEquals(2, chunks.size());
        assertEquals("response.completed", MAPPER.readTree(chunks.get(0)).path("type").asText());
        assertEquals("r1", MAPPER.readTree(chunks.get(0)).path("response").path("id").asText());
        assertNull(chunks.get(1));
    }

    @Test
    void multilineDataIsOneCompleteJsonEvent() {
        List<String> chunks = new ArrayList<>();
        String data = "{\"type\":\"response.completed\",\n\"response\":{\"id\":\"r1\"}}";
        parse("data: {\"type\":\"response.completed\",\ndata:\"response\":{\"id\":\"r1\"}}\n\n", chunks);
        assertEquals(Arrays.asList(data, null), chunks);
    }

    @Test
    void jsonTypeTakesPrecedenceOverEvent() {
        List<String> chunks = new ArrayList<>();
        String failed = "{\"type\":\"response.failed\"}";
        parse("event:response.completed\n" + frame(failed), chunks);
        assertEquals(List.of(failed), chunks);
    }

    @Test
    void eventDoesNotLeakIntoNextFrame() {
        List<String> chunks = new ArrayList<>();
        String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}";
        assertThrows(BizException.class, () -> parse("event:response.completed\n\n" + frame(delta), chunks));
        assertEquals(List.of(delta), chunks);
    }

    @Test
    void malformedJsonFailsWithoutSuccessSignal() {
        List<String> chunks = new ArrayList<>();
        BizException error = assertThrows(BizException.class, () -> parse("data:{broken}\n\n", chunks));
        assertEquals(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED, error.getErrorCode());
        assertTrue(chunks.isEmpty());
    }

    @Test
    void downstreamFailureClosesSourceImmediatelyAndDoesNotDispatchMoreEvents() {
        String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}";
        Buffer data = new Buffer().writeUtf8(frame(delta) + frame("{\"type\":\"response.completed\"}"));
        AtomicBoolean closed = new AtomicBoolean();
        ResponseBody body = ResponseBody.create(MediaType.parse("text/event-stream"), -1,
                Okio.buffer(new ForwardingSource(data) {
                    @Override
                    public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                }));
        List<String> chunks = new ArrayList<>();
        UncheckedIOException downstream = new UncheckedIOException(new IOException("disconnected"));
        assertSame(downstream, assertThrows(UncheckedIOException.class, () -> ResponsesStreamUtil.parse(body, chunk -> {
            chunks.add(chunk);
            throw downstream;
        })));
        assertTrue(closed.get());
        assertEquals(List.of(delta), chunks);
    }

    @Test
    void cancellableReusesExistingWrapperAndForwardsDataAndTerminal() {
        List<String> chunks = new ArrayList<>();
        var consumer = ResponsesStreamUtil.cancellable(chunks::add);
        assertSame(consumer, ResponsesStreamUtil.cancellable(consumer));
        assertFalse(consumer.isCancelled());
        assertDoesNotThrow(consumer::checkCancelled);
        consumer.accept("delta");
        consumer.accept(null);
        assertEquals(Arrays.asList("delta", null), chunks);
    }

    @Test
    void successfulTerminalReleasesResourcesBeforeCompletionLifecycle() {
        Call call = mock(Call.class);
        Future<?> task = mock(Future.class);
        Future<?> lateTask = mock(Future.class);
        var consumer = ResponsesStreamUtil.cancellable(chunk -> {});
        consumer.setCall(call);
        consumer.setTask(task);
        consumer.accept(null);
        consumer.cancel();
        consumer.setTask(lateTask);
        verifyNoInteractions(call, task, lateTask);
    }

    @Test
    void cancellationDoesNotWaitForInFlightDownstreamAndSuppressesNewCallbacks() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        List<String> chunks = new ArrayList<>();
        var consumer = ResponsesStreamUtil.cancellable(chunk -> {
            entered.countDown();
            try {
                assertTrue(unblock.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            chunks.add(chunk);
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var dispatch = executor.submit(() -> consumer.accept("delta"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CountDownLatch cancelling = new CountDownLatch(1);
            var cancel = executor.submit(() -> {
                cancelling.countDown();
                consumer.cancel();
            });
            assertTrue(cancelling.await(5, TimeUnit.SECONDS));
            cancel.get(5, TimeUnit.SECONDS);
            assertTrue(consumer.isCancelled());
            unblock.countDown();
            dispatch.get(5, TimeUnit.SECONDS);
            consumer.accept("late");
            consumer.accept("[ERROR]");
            consumer.accept(null);
            assertEquals(List.of("delta"), chunks);
        } finally {
            unblock.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelIsIdempotentCancelsResourcesAndSuppressesAllCallbacks() {
        List<String> chunks = new ArrayList<>();
        var consumer = ResponsesStreamUtil.cancellable(chunks::add);
        Call call = mock(Call.class);
        Future<?> task = mock(Future.class);
        consumer.setCall(call);
        consumer.setTask(task);
        consumer.cancel();
        consumer.cancel();
        consumer.accept("delta");
        consumer.accept("[ERROR]");
        consumer.accept(null);
        assertTrue(consumer.isCancelled());
        assertThrows(CancellationException.class, consumer::checkCancelled);
        assertTrue(chunks.isEmpty());
        verify(call).cancel();
        verify(task).cancel(true);
        verifyNoMoreInteractions(call, task);
    }

    @Test
    void cancellationBeforeResourceAssignmentCancelsLateCallAndFuture() {
        var consumer = ResponsesStreamUtil.cancellable(chunk -> fail("cancelled callback"));
        Call call = mock(Call.class);
        Future<?> task = mock(Future.class);
        consumer.cancel();
        consumer.setCall(call);
        consumer.setTask(task);
        consumer.cancel();
        verify(call).cancel();
        verify(task).cancel(true);
        verifyNoMoreInteractions(call, task);
    }

    @Test
    void callbackFailureAutomaticallyCancelsAndRethrowsOriginalException() {
        IllegalStateException failure = new IllegalStateException("disconnected");
        List<String> chunks = new ArrayList<>();
        var consumer = ResponsesStreamUtil.cancellable(chunk -> {
            chunks.add(chunk);
            throw failure;
        });
        Call call = mock(Call.class);
        Future<?> task = mock(Future.class);
        consumer.setCall(call);
        consumer.setTask(task);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> consumer.accept("delta")));
        consumer.accept("[ERROR]");
        consumer.accept(null);
        assertEquals(List.of("delta"), chunks);
        assertTrue(consumer.isCancelled());
        verify(call).cancel();
        verify(task).cancel(true);
        verifyNoMoreInteractions(call, task);
    }

    @Test
    void releaseDropsResourcesAndDoesNotRetainLateFuture() {
        var consumer = ResponsesStreamUtil.cancellable(chunk -> {});
        Call call = mock(Call.class);
        Future<?> task = mock(Future.class);
        Future<?> lateTask = mock(Future.class);
        consumer.setCall(call);
        consumer.setTask(task);
        consumer.release();
        consumer.release();
        consumer.setTask(lateTask);
        assertNull(ReflectionTestUtils.getField(consumer, "call"));
        assertNull(ReflectionTestUtils.getField(consumer, "task"));
        consumer.cancel();
        verifyNoInteractions(call, task, lateTask);
    }

    @Test
    void releaseDoesNotRetainLateCall() {
        var consumer = ResponsesStreamUtil.cancellable(chunk -> {});
        Call lateCall = mock(Call.class);
        consumer.release();
        consumer.setCall(lateCall);
        assertNull(ReflectionTestUtils.getField(consumer, "call"));
        consumer.cancel();
        verifyNoInteractions(lateCall);
    }

    @Test
    void releaseAfterCancellationClearsResourcesWithoutCancellingAgain() {
        var consumer = ResponsesStreamUtil.cancellable(chunk -> {});
        Call call = mock(Call.class);
        Future<?> task = mock(Future.class);
        consumer.setCall(call);
        consumer.setTask(task);
        consumer.cancel();
        consumer.release();
        assertNull(ReflectionTestUtils.getField(consumer, "call"));
        assertNull(ReflectionTestUtils.getField(consumer, "task"));
        consumer.cancel();
        verify(call).cancel();
        verify(task).cancel(true);
        verifyNoMoreInteractions(call, task);
    }

    private void assertFailureEvent(String type) {
        List<String> chunks = new ArrayList<>();
        String failure = "{\"type\":\"" + type + "\",\"error\":{\"message\":\"failed\"}}";
        parse(frame(failure) + frame("{\"type\":\"response.completed\"}"), chunks);
        assertEquals(List.of(failure), chunks);
    }

    private static String frame(String json) {
        return "data:" + json + "\n\n";
    }

    private static void parse(String sse, List<String> chunks) {
        try (ResponseBody body = ResponseBody.create(sse, MediaType.parse("text/event-stream"))) {
            ResponsesStreamUtil.parse(body, chunks::add);
        }
    }
}

