package com.ai.agent.application.service.impl;

import com.ai.agent.application.model.llm.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CompatibleReasoningTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Object> services() {
        return Stream.of(new OpenAIServiceImpl(null, null, null, null),
                new DoubaoServiceImpl(null, null, null, null), new GlmServiceImpl(null, null, null, null),
                new QwenServiceImpl(null, null, null, null), new MoonshotServiceImpl(null, null, null, null),
                new MiniMaxServiceImpl(null, null, null, null), new OllamaServiceImpl(null, null, null, null),
                new QianfanServiceImpl(null, null, null, null), new TokenHubServiceImpl(null, null, null, null),
                new MimoServiceImpl(null, null, null, null));
    }

    @ParameterizedTest
    @MethodSource("services")
    void assistantReasoningAndToolCallsSurviveRoundTrip(Object service) throws IOException {
        String json = """
                {"choices":[{"message":{"content":"answer","reasoning_content":"reason","tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"lookup","arguments":"{}"}}
                ]},"finish_reason":"tool_calls"}]}
                """;
        LlmResponse response = ReflectionTestUtils.invokeMethod(service, "parseResponse", json, "test-model");
        LlmChoice choice = response.getChoices().get(0);
        assertEquals("reason", choice.getReasoningContent());
        assertEquals("answer", choice.getContent());
        assertEquals("tool_calls", choice.getFinishReason());
        LlmMessage assistant = LlmMessage.ofToolCalls(choice.getToolCalls(), choice.getContent());
        assistant.setReasoningContent(choice.getReasoningContent());
        JsonNode messages = messages(service, assistant, LlmMessage.ofToolResult("call-1", "result"));
        assertEquals("reason", messages.path(0).path("reasoning_content").asText());
        assertEquals("answer", messages.path(0).path("content").asText());
        assertEquals(MAPPER.readTree(json).path("choices").path(0).path("message").path("tool_calls"),
                messages.path(0).path("tool_calls"));
        assertEquals("call-1", messages.path(1).path("tool_call_id").asText());
        assertEquals("result", messages.path(1).path("content").asText());
        assertFalse(messages.path(1).has("reasoning_content"));
        assistant.setContents(null);
        assertTrue(messages(service, assistant).path(0).path("content").isNull());
        assertEquals("reason", messages(service, assistant).path(0).path("reasoning_content").asText());
    }

    @ParameterizedTest
    @MethodSource("services")
    void onlyAssistantWithNonNullReasoningAddsField(Object service) {
        LlmMessage assistant = LlmMessage.ofText("assistant", "plain");
        LlmMessage user = LlmMessage.ofText("user", "question");
        user.setReasoningContent("not assistant");
        JsonNode messages = messages(service, assistant, user,
                LlmMessage.ofToolCalls(List.of(LlmToolCall.builder().id("a").name("lookup").arguments("{}").build())));
        for (JsonNode message : messages) assertFalse(message.has("reasoning_content"));
        assistant.setReasoningContent("");
        assertEquals("", messages(service, assistant).path(0).path("reasoning_content").asText(null));
        assistant.setReasoningContent("reason");
        assertEquals("reason", messages(service, assistant).path(0).path("reasoning_content").asText());
    }

    @ParameterizedTest
    @MethodSource("services")
    void streamSeparatesReasoningContentAndToolCalls(Object service) throws IOException {
        String sse = """
                data: {"choices":[{"delta":{"reasoning_content":null}}]}
                data: {"choices":[{"delta":{"reasoning_content":""}}]}
                data: {"choices":[{"delta":{"reasoning_content":"first"}}]}
                data: {"choices":[{"delta":{"reasoning_content":"second","content":"answer","tool_calls":[{"index":0,"id":"call-1","function":{"name":"lookup","arguments":"{"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}
                data: [DONE]
                """;
        List<String> chunks = stream(service, sse);
        assertEquals(5, chunks.size());
        assertEquals("[REASONING]first", chunks.get(0));
        assertEquals("[REASONING]second", chunks.get(1));
        assertEquals("answer", chunks.get(2));
        assertTrue(chunks.get(3).startsWith("[TOOL_CALLS]"));
        JsonNode call = MAPPER.readTree(chunks.get(3).substring("[TOOL_CALLS]".length())).path(0);
        assertEquals("call-1", call.path("id").asText());
        assertEquals("lookup", call.path("name").asText());
        assertEquals("{}", call.path("arguments").asText());
        assertNull(chunks.get(4));
        assertEquals(Arrays.asList("plain", null), stream(service,
                "data: {\"choices\":[{\"delta\":{\"content\":\"plain\"}}]}\ndata: [DONE]\n"));
    }

    @ParameterizedTest
    @MethodSource("services")
    void streamEscapesOnlyConflictingTextWhileKeepingReasoning(Object service) throws IOException {
        for (String text : List.of("[REASONING]literal", "[THINKING_BLOCKS]literal", "[TEXT]literal",
                "[TEXT][TEXT]literal", "plain [REASONING]literal", "plain")) {
            String sse = "data: " + MAPPER.writeValueAsString(Map.of("choices", List.of(
                    Map.of("delta", Map.of("content", text, "reasoning_content", "reason")))))
                    + "\ndata: [DONE]\n";
            String expected = text.startsWith("[") ? "[TEXT]" + text : text;
            assertEquals(Arrays.asList("[REASONING]reason", expected, null), stream(service, sse));
        }
    }

    private static JsonNode messages(Object service, LlmMessage... messages) {
        LlmRequest request = LlmRequest.builder().messages(Arrays.asList(messages)).build();
        Object result = ReflectionTestUtils.invokeMethod(service, "buildMessages", request);
        return MAPPER.valueToTree(result);
    }

    private static List<String> stream(Object service, String sse) {
        List<String> chunks = new ArrayList<>();
        try (ResponseBody body = ResponseBody.create(sse, MediaType.parse("text/event-stream"))) {
            ReflectionTestUtils.invokeMethod(service, "parseStreamResponse", body, "test-model", (Consumer<String>) chunks::add);
        }
        return chunks;
    }
}

