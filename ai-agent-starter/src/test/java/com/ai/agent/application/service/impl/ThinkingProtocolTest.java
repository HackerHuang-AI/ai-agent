package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ThinkingProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AnthropicServiceImpl anthropic = new AnthropicServiceImpl(null, null, null, null);
    private final GeminiServiceImpl gemini = new GeminiServiceImpl(null, null, null, null);

    @Test
    void claudeSyncThinkingAndRedactedBlocksSurviveToolContinuation() throws IOException {
        String json = """
                {"content":[
                  {"type":"thinking","thinking":"first","signature":"sig-1","extra":{"a":1}},
                  {"type":"redacted_thinking","data":"opaque"},
                  {"type":"thinking","thinking":"second","signature":"sig-2"},
                  {"type":"text","text":"answer"},
                  {"type":"tool_use","id":"call-1","name":"lookup","input":{"q":"x"}}
                ],"stop_reason":"tool_use"}
                """;
        LlmChoice choice = parseResponse(anthropic, json).getChoices().get(0);
        assertEquals("firstsecond", choice.getReasoningContent());
        assertEquals("answer", choice.getContent());
        assertEquals("tool_calls", choice.getFinishReason());
        assertEquals(MAPPER.readTree(json).path("content").get(0), MAPPER.valueToTree(choice.getThinkingBlocks().get(0)));
        assertEquals("opaque", choice.getThinkingBlocks().get(1).get("data"));
        assertEquals("sig-2", choice.getThinkingBlocks().get(2).get("signature"));

        LlmMessage assistant = LlmMessage.ofToolCalls(choice.getToolCalls(), choice.getContent());
        assistant.setThinkingBlocks(choice.getThinkingBlocks());
        JsonNode messages = requestBody(anthropic, assistant, LlmMessage.ofToolResult("call-1", "result")).path("messages");
        assertEquals(MAPPER.readTree(json).path("content"), messages.path(0).path("content"));
        assertEquals("tool_result", messages.path(1).path("content").path(0).path("type").asText());
        assertEquals("call-1", messages.path(1).path("content").path(0).path("tool_use_id").asText());
    }

    @Test
    void claudePlainAssistantCanReplayThinkingWithoutInventingSignature() throws IOException {
        List<Map<String, Object>> blocks = List.of(Map.of("type", "thinking", "thinking", "unsigned"),
                Map.of("type", "redacted_thinking", "data", "raw"));
        LlmMessage assistant = LlmMessage.ofText("assistant", "answer");
        assistant.setThinkingBlocks(blocks);
        JsonNode content = requestBody(anthropic, assistant).path("messages").path(0).path("content");
        assertEquals(MAPPER.valueToTree(blocks.get(0)), content.path(0));
        assertFalse(content.path(0).has("signature"));
        assertEquals(MAPPER.valueToTree(blocks.get(1)), content.path(1));
        assertEquals("answer", content.path(2).path("text").asText());
        assertEquals("unsigned", blocks.get(0).get("thinking"));
    }

    @Test
    void claudeThinkingPreservesAllAssistantContentForSyncAndStreamRequests() throws IOException {
        List<Map<String, Object>> blocks = List.of(Map.of("type", "thinking", "thinking", "reason", "signature", "signed"));
        for (List<MessageContent> contents : List.of(
                List.of(MessageContent.ofText("first"), MessageContent.ofText("second")),
                List.of(MessageContent.ofText("first"), MessageContent.ofImage("https://example.com/image.png"),
                        MessageContent.ofText("second")))) {
            LlmMessage assistant = LlmMessage.ofMultiModal("assistant", contents);
            JsonNode original = MAPPER.valueToTree(ReflectionTestUtils.invokeMethod(anthropic, "buildContentArray", assistant));
            assistant.setThinkingBlocks(blocks);
            for (boolean stream : List.of(false, true)) {
                JsonNode replay = requestBody(anthropic, stream, assistant).path("messages").path(0).path("content");
                assertEquals(MAPPER.valueToTree(blocks.get(0)), replay.path(0));
                assertEquals(original.size() + 1, replay.size());
                for (int i = 0; i < original.size(); i++) assertEquals(original.get(i), replay.get(i + 1));
            }
        }
        LlmMessage thinkingOnly = LlmMessage.builder().role("assistant").thinkingBlocks(blocks).build();
        for (boolean stream : List.of(false, true)) {
            assertEquals(MAPPER.valueToTree(blocks), requestBody(anthropic, stream, thinkingOnly)
                    .path("messages").path(0).path("content"));
            thinkingOnly.setContents(List.of(MessageContent.ofText(null)));
            assertEquals(MAPPER.valueToTree(blocks), requestBody(anthropic, stream, thinkingOnly)
                    .path("messages").path(0).path("content"));
            thinkingOnly.setContents(null);
        }
    }

    @Test
    void claudeThinkingToolCallsKeepFirstTextStrategy() throws IOException {
        LlmMessage assistant = LlmMessage.ofToolCalls(List.of(
                LlmToolCall.builder().id("call-1").name("lookup").arguments("{}").build()));
        assistant.setContents(List.of(MessageContent.ofText("first"),
                MessageContent.ofImage("https://example.com/image.png"), MessageContent.ofText("second")));
        assistant.setThinkingBlocks(List.of(Map.of("type", "thinking", "thinking", "reason", "signature", "signed")));
        JsonNode content = requestBody(anthropic, assistant).path("messages").path(0).path("content");
        assertEquals(3, content.size());
        assertEquals("thinking", content.path(0).path("type").asText());
        assertEquals("first", content.path(1).path("text").asText());
        assertEquals("tool_use", content.path(2).path("type").asText());
    }

    @Test
    void claudeStreamAccumulatesSignaturesAndEmitsThinkingBeforeToolsAndDone() throws IOException {
        String sse = claudeFrame("content_block_start", """
                {"index":0,"content_block":{"type":"thinking","thinking":"","signature":"","extra":7}}
                """)
                + claudeFrame("content_block_delta", """
                {"index":0,"delta":{"type":"thinking_delta","thinking":"first"}}
                """)
                + claudeFrame("content_block_delta", """
                {"index":0,"delta":{"type":"thinking_delta","thinking":"second"}}
                """)
                + claudeFrame("content_block_delta", """
                {"index":0,"delta":{"type":"signature_delta","signature":"sig-"}}
                """)
                + claudeFrame("content_block_delta", """
                {"index":0,"delta":{"type":"signature_delta","signature":"tail"}}
                """)
                + claudeFrame("content_block_stop", "{\"index\":0}")
                + claudeFrame("content_block_start", """
                {"index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}
                """)
                + claudeFrame("content_block_stop", "{\"index\":1}")
                + claudeFrame("content_block_delta", """
                {"index":2,"delta":{"type":"text_delta","text":"answer"}}
                """)
                + claudeFrame("content_block_start", """
                {"index":3,"content_block":{"type":"tool_use","id":"call-1","name":"lookup","input":{}}}
                """)
                + claudeFrame("content_block_delta", """
                {"index":3,"delta":{"type":"input_json_delta","partial_json":"{}"}}
                """)
                + claudeFrame("content_block_stop", "{\"index\":3}")
                + claudeFrame("message_stop", "{}");
        List<String> chunks = parseStream(anthropic, sse);
        assertEquals(7, chunks.size());
        assertEquals("[REASONING]first", chunks.get(0));
        assertEquals("[REASONING]second", chunks.get(1));
        JsonNode thinking = payload(chunks.get(2), "[THINKING_BLOCKS]").path(0);
        assertEquals("firstsecond", thinking.path("thinking").asText());
        assertEquals("sig-tail", thinking.path("signature").asText());
        assertEquals(7, thinking.path("extra").asInt());
        assertEquals("opaque", payload(chunks.get(3), "[THINKING_BLOCKS]").path(0).path("data").asText());
        assertEquals("answer", chunks.get(4));
        assertEquals("{}", payload(chunks.get(5), "[TOOL_CALLS]").path(0).path("arguments").asText());
        assertNull(chunks.get(6));
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(MAPPER.convertValue(thinking, new TypeReference<Map<String, Object>>() {}));
        blocks.add(MAPPER.convertValue(payload(chunks.get(3), "[THINKING_BLOCKS]").path(0),
                new TypeReference<Map<String, Object>>() {}));
        LlmMessage assistant = LlmMessage.ofToolCalls(List.of(
                LlmToolCall.builder().id("call-1").name("lookup").arguments("{}").build()), "answer");
        assistant.setThinkingBlocks(blocks);
        JsonNode replay = requestBody(anthropic, assistant).path("messages").path(0).path("content");
        assertEquals(thinking, replay.path(0));
        assertEquals("opaque", replay.path(1).path("data").asText());
    }

    @Test
    void claudeErrorDoesNotEmitSuccessOrTools() throws IOException {
        assertEquals(List.of("[ERROR]"), parseStream(anthropic,
                claudeFrame("error", "{\"type\":\"error\",\"error\":{\"message\":\"failed\"}}")
                        + claudeFrame("message_stop", "{}")));
        assertEquals(List.of("[ERROR]"), parseStream(anthropic,
                "data: {\"type\":\"error\",\"error\":{\"message\":\"failed\"}}\n\n"));
    }

    @Test
    void claudeTruncatedStreamDoesNotFlushUnfinishedThinkingOrTools() throws IOException {
        String sse = claudeFrame("content_block_start", """
                {"index":0,"content_block":{"type":"thinking","thinking":"reason"}}
                """) + claudeFrame("content_block_delta", """
                {"index":0,"delta":{"type":"signature_delta","signature":"partial"}}
                """) + claudeFrame("content_block_start", """
                {"index":1,"content_block":{"type":"tool_use","id":"call-1","name":"lookup","input":{}}}
                """);
        List<String> chunks = new ArrayList<>();
        BizException error = assertThrows(BizException.class, () -> parseStream(anthropic, sse, chunks));
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, error.getErrorCode());
        assertEquals(List.of("[REASONING]reason"), chunks);
    }

    @Test
    void claudeCompletedThinkingBlockWithoutMessageStopIsNotSuccess() throws IOException {
        String sse = claudeFrame("content_block_start", """
                {"index":0,"content_block":{"type":"thinking","thinking":"reason","signature":"signed"}}
                """) + claudeFrame("content_block_stop", "{\"index\":0}");
        List<String> chunks = new ArrayList<>();
        BizException error = assertThrows(BizException.class, () -> parseStream(anthropic, sse, chunks));
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, error.getErrorCode());
        assertEquals(2, chunks.size());
        assertEquals("signed", payload(chunks.get(1), "[THINKING_BLOCKS]").path(0).path("signature").asText());
        assertFalse(chunks.contains(null));
    }

    @Test
    void claudeMalformedSignatureOrStopCannotBecomeSuccess() throws IOException {
        String start = claudeFrame("content_block_start", """
                {"index":0,"content_block":{"type":"thinking","thinking":"reason"}}
                """);
        for (String invalid : List.of(
                "event: content_block_delta\ndata: {\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"broken\n\n",
                "event: message_stop\ndata: {broken}\n\n")) {
            List<String> chunks = new ArrayList<>();
            BizException error = assertThrows(BizException.class, () -> parseStream(anthropic,
                    start + invalid + claudeFrame("message_stop", "{}"), chunks));
            assertEquals(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED, error.getErrorCode());
            assertEquals(List.of("[REASONING]reason"), chunks);
        }
        List<String> chunks = new ArrayList<>();
        BizException error = assertThrows(BizException.class,
                () -> parseStream(anthropic, start + "event: message_stop\n", chunks));
        assertEquals(ErrorCodeEnum.LLM_CALL_FAILED, error.getErrorCode());
        assertEquals(List.of("[REASONING]reason"), chunks);
        assertEquals(List.of("[REASONING]reason", "[ERROR]"), parseStream(anthropic,
                start + "event: message_stop\n" + "data: {\"type\":\"error\"}\n\n"));
    }

    @Test
    void geminiSyncExtraContentSurvivesToolContinuation() throws IOException {
        String json = """
                {"choices":[{"message":{"content":"answer","reasoning_content":"reason","tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"},
                   "extra_content":{"google":{"thought_signature":"signed","other":1},"vendor":{"x":true}}}
                ]},"finish_reason":"tool_calls"}]}
                """;
        LlmChoice choice = parseResponse(gemini, json).getChoices().get(0);
        assertEquals("reason", choice.getReasoningContent());
        JsonNode original = MAPPER.readTree(json).path("choices").path(0).path("message").path("tool_calls").path(0);
        assertEquals(original.path("extra_content"), MAPPER.valueToTree(choice.getToolCalls().get(0).getExtraContent()));
        LlmMessage assistant = LlmMessage.ofToolCalls(choice.getToolCalls(), choice.getContent());
        assistant.setReasoningContent(choice.getReasoningContent());
        JsonNode messages = requestBody(gemini, assistant, LlmMessage.ofToolResult("call-1", "result")).path("messages");
        assertEquals("reason", messages.path(0).path("reasoning_content").asText());
        assertEquals(original, messages.path(0).path("tool_calls").path(0));
        assertEquals("call-1", messages.path(1).path("tool_call_id").asText());
    }

    @Test
    void geminiSyncReasoningCanBeReplayedOnlyForAssistant() throws IOException {
        LlmChoice choice = parseResponse(gemini, """
                {"choices":[{"message":{"content":"answer","reasoning_content":"reason"},"finish_reason":"stop"}]}
                """).getChoices().get(0);
        LlmMessage assistant = LlmMessage.ofText("assistant", choice.getContent());
        assistant.setReasoningContent(choice.getReasoningContent());
        LlmMessage user = LlmMessage.ofText("user", "question");
        user.setReasoningContent("not assistant");
        JsonNode messages = requestBody(gemini, assistant, user, LlmMessage.ofText("assistant", "plain")).path("messages");
        assertEquals("reason", messages.path(0).path("reasoning_content").asText());
        assertFalse(messages.path(1).has("reasoning_content"));
        assertFalse(messages.path(2).has("reasoning_content"));
        assistant.setReasoningContent("");
        assertEquals("", requestBody(gemini, assistant).path("messages").path(0).path("reasoning_content").asText());
    }

    @Test
    void geminiStreamMergesExtraContentByIndexAndReplaysSignature() throws IOException {
        String sse = geminiFrame("""
                {"reasoning_content":"reason","tool_calls":[
                  {"index":0,"id":"a","function":{"name":"lookup","arguments":"{"},
                   "extra_content":{"google":{"thought_signature":"sig-","first":1},"vendor":{"x":true}}},
                  {"index":1,"id":"b","function":{"name":"other","arguments":"{}"},
                   "extra_content":{"google":{"thought_signature":"second"}}}
                ]}
                """) + geminiFrame("""
                {"content":"answer","tool_calls":[
                  {"index":0,"function":{"arguments":"}"},"extra_content":{"google":{"second":2}}}
                ]}
                """) + geminiFrame("""
                {"tool_calls":[{"index":0,"extra_content":{"google":{"thought_signature":"tail"}}}]}
                """) + "data: [DONE]\n\n";
        List<String> chunks = parseStream(gemini, sse);
        assertEquals(4, chunks.size());
        assertEquals("[REASONING]reason", chunks.get(0));
        assertEquals("answer", chunks.get(1));
        JsonNode calls = payload(chunks.get(2), "[TOOL_CALLS]");
        assertEquals("{}", calls.path(0).path("arguments").asText());
        JsonNode extra = calls.path(0).path("extra_content");
        assertEquals("sig-tail", extra.path("google").path("thought_signature").asText());
        assertEquals(1, extra.path("google").path("first").asInt());
        assertEquals(2, extra.path("google").path("second").asInt());
        assertTrue(extra.path("vendor").path("x").asBoolean());
        assertEquals("second", calls.path(1).path("extra_content").path("google").path("thought_signature").asText());
        assertNull(chunks.get(3));
        LlmToolCall call = LlmToolCall.builder().id("a").name("lookup").arguments("{}")
                .extraContent(MAPPER.convertValue(extra, new TypeReference<Map<String, Object>>() {})).build();
        assertEquals(extra, requestBody(gemini, LlmMessage.ofToolCalls(List.of(call))).path("messages")
                .path(0).path("tool_calls").path(0).path("extra_content"));
    }

    @Test
    void claudeGeminiAndDeepseekParsersEscapeConflictingTextWithoutChangingReasoning() throws IOException {
        DeepseekServiceImpl deepseek = new DeepseekServiceImpl(null, null, null, null);
        for (String text : List.of("[REASONING]literal", "[THINKING_BLOCKS]literal", "[TEXT]literal",
                "[TEXT][TEXT]literal", "plain [THINKING_BLOCKS]literal", "plain")) {
            String expected = text.startsWith("[") ? "[TEXT]" + text : text;
            String sse = geminiFrame(MAPPER.writeValueAsString(Map.of("content", text, "reasoning_content", "reason")))
                    + "data: [DONE]\n\n";
            for (Object service : List.of(gemini, deepseek)) {
                assertEquals(Arrays.asList("[REASONING]reason", expected, null), parseStream(service, sse));
            }
            String claude = claudeFrame("content_block_start", """
                    {"index":0,"content_block":{"type":"thinking","thinking":"reason","signature":"signed"}}
                    """) + claudeFrame("content_block_stop", "{\"index\":0}")
                    + claudeFrame("content_block_delta", MAPPER.writeValueAsString(Map.of("index", 1,
                            "delta", Map.of("type", "text_delta", "text", text))))
                    + claudeFrame("message_stop", "{}");
            List<String> chunks = parseStream(anthropic, claude);
            assertEquals("[REASONING]reason", chunks.get(0));
            assertEquals(MAPPER.readTree("""
                    [{"type":"thinking","thinking":"reason","signature":"signed"}]
                    """), payload(chunks.get(1), "[THINKING_BLOCKS]"));
            assertEquals(expected, chunks.get(2));
            assertNull(chunks.get(3));
            assertEquals(4, chunks.size());
        }
    }

    @Test
    void legacyRequestsDoNotInjectThinkingOrNullExtraContent() throws IOException {
        LlmMessage assistant = LlmMessage.ofText("assistant", "plain");
        assistant.setReasoningContent("not a signed thinking block");
        assertEquals("plain", requestBody(anthropic, assistant).path("messages").path(0).path("content").asText());
        assertEquals(Arrays.asList("plain", null), parseStream(anthropic,
                claudeFrame("content_block_delta", "{\"delta\":{\"type\":\"text_delta\",\"text\":\"plain\"}}")
                        + claudeFrame("message_stop", "{}")));
        assertEquals(Arrays.asList("plain", null), parseStream(gemini,
                geminiFrame("{\"content\":\"plain\"}") + "data: [DONE]\n\n"));
        LlmToolCall call = LlmToolCall.builder().id("a").name("lookup").arguments("{}").build();
        JsonNode tool = requestBody(gemini, LlmMessage.ofToolCalls(List.of(call))).path("messages")
                .path(0).path("tool_calls").path(0);
        assertFalse(tool.has("extra_content"));
        assertNull(parseResponse(gemini, """
                {"choices":[{"message":{"tool_calls":[{"id":"a","function":{"name":"lookup","arguments":"{}"}}]}}]}
                """).getChoices().get(0).getToolCalls().get(0).getExtraContent());
        assertNull(parseResponse(anthropic, "{\"content\":[{\"type\":\"text\",\"text\":\"plain\"}]}")
                .getChoices().get(0).getThinkingBlocks());
    }

    private static LlmResponse parseResponse(Object service, String json) {
        return ReflectionTestUtils.invokeMethod(service, "parseResponse", json, "test-model");
    }

    private static JsonNode requestBody(Object service, LlmMessage... messages) throws IOException {
        return requestBody(service, false, messages);
    }

    private static JsonNode requestBody(Object service, boolean stream, LlmMessage... messages) throws IOException {
        LlmRequest request = LlmRequest.builder().modelCode("test-model").messages(Arrays.asList(messages)).build();
        String body = ReflectionTestUtils.invokeMethod(service, "buildRequestBody", request, stream);
        return MAPPER.readTree(body);
    }

    private static List<String> parseStream(Object service, String sse) {
        List<String> chunks = new ArrayList<>();
        parseStream(service, sse, chunks);
        return chunks;
    }

    private static void parseStream(Object service, String sse, List<String> chunks) {
        try (ResponseBody body = ResponseBody.create(sse, MediaType.parse("text/event-stream"))) {
            ReflectionTestUtils.invokeMethod(service, "parseStreamResponse", body, "test-model", (Consumer<String>) chunks::add);
        }
    }

    private static String claudeFrame(String event, String json) throws IOException {
        return "event: " + event + "\ndata: " + MAPPER.writeValueAsString(MAPPER.readTree(json)) + "\n\n";
    }

    private static String geminiFrame(String delta) throws IOException {
        return "data: " + MAPPER.writeValueAsString(Map.of("choices", List.of(Map.of("delta", MAPPER.readTree(delta))))) + "\n\n";
    }

    private static JsonNode payload(String chunk, String prefix) throws IOException {
        assertTrue(chunk.startsWith(prefix));
        return MAPPER.readTree(chunk.substring(prefix.length()));
    }
}

