package com.ai.agent.application.utils;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Call;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class ResponsesStreamUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResponsesStreamUtil() {}

    public static CancellableConsumer cancellable(Consumer<String> consumer) {
        return consumer instanceof CancellableConsumer cancellable ? cancellable : new CancellableConsumer(consumer);
    }

    public static final class CancellableConsumer implements Consumer<String> {
        private final Consumer<String> downstream;
        private volatile boolean cancelled;
        private Call call;
        private Future<?> task;
        private boolean finished;

        public CancellableConsumer(Consumer<String> downstream) {
            this.downstream = downstream;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void checkCancelled() {
            if (cancelled) throw new CancellationException();
        }

        public synchronized void setCall(Call call) {
            if (finished) return;
            this.call = call;
            if (cancelled) call.cancel();
        }

        public synchronized void setTask(Future<?> task) {
            if (finished) return;
            this.task = task;
            if (cancelled) task.cancel(true);
        }

        public synchronized void cancel() {
            if (cancelled) return;
            cancelled = true;
            if (call != null) call.cancel();
            if (task != null) task.cancel(true);
        }

        public synchronized void release() {
            finished = true;
            call = null;
            task = null;
        }

        @Override
        public void accept(String data) {
            if (cancelled) return;
            if (data == null) release();
            try {
                downstream.accept(data);
            } catch (RuntimeException e) {
                cancel();
                throw e;
            }
        }
    }

    public static void parse(ResponseBody responseBody, Consumer<String> chunkConsumer) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            boolean hasData = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (hasData && dispatch(event, data.toString(), chunkConsumer)) return;
                    event = null;
                    data.setLength(0);
                    hasData = false;
                    continue;
                }
                if (line.startsWith(":")) continue;
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                if ("event".equals(field)) {
                    event = value;
                } else if ("data".equals(field)) {
                    if (hasData) data.append('\n');
                    data.append(value);
                    hasData = true;
                }
            }
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        }
    }

    private static boolean dispatch(String event, String data, Consumer<String> chunkConsumer) throws IOException {
        if ("[DONE]".equals(data.trim())) throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        JsonNode root = MAPPER.readTree(data);
        if (root == null || !root.isObject()) throw new BizException(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED);
        String type = root.path("type").asText("");
        if (type.isEmpty() && event != null && !event.isEmpty()) {
            ((ObjectNode) root).put("type", event);
            type = event;
            data = MAPPER.writeValueAsString(root);
        }
        chunkConsumer.accept(data);
        if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            chunkConsumer.accept(null);
            return true;
        }
        return "response.failed".equals(type) || "error".equals(type);
    }
}

