package com.ai.agent.application.service.impl;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.utils.NativeInputUtil;
import com.ai.agent.application.utils.ResponsesApiUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NativeInputTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "https://example.com/media";
    private static final String AUDIO = "data:audio/wav;base64,YQ==";
    private static final String PDF = "data:application/pdf;base64,YQ==";

    static Stream<LlmService> chatAdapters() {
        return Stream.concat(ProtocolAdaptersTest.responsesAdapters(), Stream.of(
                new AnthropicServiceImpl(null, null, null, null), new GeminiServiceImpl(null, null, null, null)));
    }

    static Stream<LlmService> responsesAdapters() {
        return ProtocolAdaptersTest.responsesAdapters();
    }

    @ParameterizedTest
    @MethodSource("chatAdapters")
    void typedChatAndLegacyTextKeepCommonFieldsProtected(LlmService adapter) throws IOException {
        for (MessageContent content : List.of(MessageContent.ofText("hello"),
                MessageContent.builder().type(ContentTypeEnum.TEXT).text("hello").build())) {
            LlmRequest request = LlmRequest.builder().modelCode("model")
                    .messages(List.of(LlmMessage.ofMultiModal("user", List.of(content))))
                    .temperature(0.5).topP(0.8).maxTokens(123)
                    .tools(List.of(Map.of("type", "function", "function", Map.of("name", "lookup", "parameters", Map.of("type", "object")))))
                    .toolChoice("auto").extraParams(Map.of("messages", "evil", "input", "evil", "model", "evil",
                            "stream", true, "temperature", 1.0, "top_p", 0.1, "max_tokens", 9,
                            "tools", "evil", "tool_choice", "evil")).build();
            JsonNode original = MAPPER.valueToTree(request);
            for (boolean stream : List.of(false, true)) {
                JsonNode body = body(adapter, "buildRequestBody", request, stream);
                JsonNode text = body.path("messages").path(0).path("content");
                assertEquals("hello", text.isArray() ? text.path(0).path("text").asText() : text.asText());
                assertEquals("model", body.path("model").asText());
                assertEquals(stream, body.path("stream").asBoolean());
                assertEquals(0.5, body.path("temperature").asDouble());
                assertEquals(0.8, body.path("top_p").asDouble());
                assertEquals(123, body.path("max_tokens").asInt());
                assertTrue(body.path("tools").isArray());
                assertNotEquals("evil", body.path("tool_choice").asText());
                assertFalse(body.has("input"));
                assertEquals(original, MAPPER.valueToTree(request));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void typedResponsesHistoryIsExactAndProtected(LlmService adapter) throws IOException {
        List<LlmResponsesInput> history = List.of(
                LlmResponsesInput.builder().type("reasoning").encryptedContent("opaque")
                        .summary(List.of(Map.of("type", "summary_text", "text", "history"))).build(),
                LlmResponsesInput.builder().type("function_call").callId("call-1").name("lookup").arguments("{}").build(),
                LlmResponsesInput.builder().type("function_call_output").callId("call-1").output("result").build(),
                LlmResponsesInput.builder().role("user").content(List.of(text("hello"))).build());
        LlmResponsesRequest request = LlmResponsesRequest.builder().model("model").input(history)
                .extraParams(Map.of("input", "evil", "messages", "evil", "model", "evil", "stream", true)).build();
        JsonNode original = MAPPER.valueToTree(request);
        for (boolean stream : List.of(false, true)) {
            JsonNode body = body(adapter, "buildResponsesRequestBody", request, stream);
            assertEquals(MAPPER.valueToTree(history), body.path("input"));
            assertEquals("model", body.path("model").asText());
            assertEquals(stream, body.path("stream").asBoolean());
            assertFalse(body.has("messages"));
            assertSame(history, request.getInput());
            assertEquals(original, MAPPER.valueToTree(request));
        }
    }

    @Test
    void confirmedChatMediaSourcesRemainPlatformSpecific() {
        chat("OpenAI", file(MessageContent.FileSource.builder().fileId("file-1").build()));
        chat("OpenAI", file(MessageContent.FileSource.builder().filename("a.pdf").fileData(PDF).build()));
        chat("OpenAI", audio(MessageContent.AudioSource.builder().data("YQ==").format("wav").build()));
        for (MessageContent.ContentSource source : List.of(
                MessageContent.ContentSource.builder().type("base64").mediaType("application/pdf").data("YQ==").build(),
                MessageContent.ContentSource.builder().type("url").url(URL).build(),
                MessageContent.ContentSource.builder().type("file").fileId("file-1").build(),
                MessageContent.ContentSource.builder().type("text").mediaType("text/plain").data("document").build())) {
            chat("Anthropic", document(source));
        }
        chat("Qwen", audio(MessageContent.AudioSource.builder().data(AUDIO).format("wav").build()));
        chat("Qwen", MessageContent.builder().type(ContentTypeEnum.VIDEO_URL)
                .videoUrl(MessageContent.VideoSource.builder().url(URL).build()).fps(2.0).build());
        chat("Doubao", file(MessageContent.FileSource.builder().fileId("file-1").build()));
        for (MessageContent.AudioSource source : List.of(
                MessageContent.AudioSource.builder().data("YQ==").format("wav").build(),
                MessageContent.AudioSource.builder().url(URL).format("mp3").build(),
                MessageContent.AudioSource.builder().fileId("file-1").build())) chat("Doubao", audio(source));
        chat("Doubao", video(MessageContent.VideoSource.builder().fileId("file-1").fps(2.0).build()));
        chat("Glm", file(MessageContent.FileSource.builder().fileUrl(URL).build()));
        chat("Glm", MessageContent.builder().type(ContentTypeEnum.FILE_URL)
                .fileUrl(MessageContent.UrlSource.builder().url(URL).build()).build());
        chat("Glm", file(MessageContent.FileSource.builder().fileData("YQ==").filename("a.pdf").build()));
        chat("Glm", audio(MessageContent.AudioSource.builder().data("YQ==").format("mp3").build()));
        chat("Glm", video(MessageContent.VideoSource.builder().url(URL).build()));
        LlmRequest longFile = LlmRequest.builder().messages(List.of(LlmMessage.ofText("system", "fileid://file-1"))).build();
        List<Map<String, Object>> wire = List.of(Map.of("role", "system", "content", "fileid://file-1"));
        assertSame(wire, NativeInputUtil.chatMessages(longFile, "Qwen", () -> wire));
    }

    @Test
    void confirmedResponsesMediaSchemasRemainExact() {
        for (String platform : List.of("OpenAI", "Doubao")) {
            responses(platform, LlmResponsesContent.builder().type("input_file").fileId("file-1").build());
            responses(platform, LlmResponsesContent.builder().type("input_file").fileUrl(URL).build());
            responses(platform, LlmResponsesContent.builder().type("input_file").filename("a.pdf").fileData(PDF).build());
        }
        for (String platform : List.of("OpenAI", "Doubao", "Qwen")) {
            responses(platform, LlmResponsesContent.builder().type("input_image").imageUrl(URL).detail("high").build());
        }
        responses("Doubao", LlmResponsesContent.builder().type("input_audio").fileId("file-1").build());
        responses("Doubao", LlmResponsesContent.builder().type("input_audio").audioUrl(AUDIO).build());
        responses("Doubao", LlmResponsesContent.builder().type("input_video").fileId("file-1").fps(2.0).build());
        responses("Doubao", LlmResponsesContent.builder().type("input_video").videoUrl(URL).fps(2.0).build());
        responses("Qwen", LlmResponsesContent.builder().type("input_audio").audioUrl(URL).format("wav").build());
        responses("Qwen", LlmResponsesContent.builder().type("input_audio").data(AUDIO).format("wav").build());
        responses("Qwen", LlmResponsesContent.builder().type("input_video").videoUrl(URL).build());
    }

    @Test
    void unconfirmedMediaAndSourceModesAreRejected() {
        for (String platform : List.of("Deepseek", "Qianfan", "Mimo", "MiniMax", "Moonshot", "TokenHub", "Ollama")) {
            for (MessageContent block : List.of(file(MessageContent.FileSource.builder().fileId("file-1").build()),
                    audio(MessageContent.AudioSource.builder().data("YQ==").format("wav").build()),
                    video(MessageContent.VideoSource.builder().url(URL).build()))) {
                error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat(platform, block));
            }
        }
        for (String platform : List.of("Deepseek", "Glm", "Qianfan", "Mimo", "MiniMax", "Moonshot", "TokenHub", "Ollama")) {
            for (String type : List.of("input_file", "input_image", "input_audio", "input_video")) {
                error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED,
                        () -> responses(platform, LlmResponsesContent.builder().type(type).fileId("file-1").build()));
            }
        }
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("OpenAI", file(MessageContent.FileSource.builder().fileUrl(URL).build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Qwen", file(MessageContent.FileSource.builder().fileId("file-1").build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Glm", video(MessageContent.VideoSource.builder().fileId("file-1").build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Anthropic", audio(MessageContent.AudioSource.builder().data("YQ==").format("wav").build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> responses("OpenAI", LlmResponsesContent.builder().type("input_audio").audioUrl(URL).build()));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Qwen", audio(MessageContent.AudioSource.builder().data(AUDIO).fileId("file-1").format("wav").build())));
        for (String type : List.of("input_image", "input_audio", "input_video")) {
            error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> responses("Qwen", LlmResponsesContent.builder().type(type).fileId("file-1").build()));
        }
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("OpenAI", audio(MessageContent.AudioSource.builder().data(AUDIO).format("wav").build())));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Anthropic", document(MessageContent.ContentSource.builder()
                .type("base64").mediaType("application/pdf").data(PDF).build())));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Doubao", file(MessageContent.FileSource.builder().build())));
    }

    @Test
    void mediaSourcesMustSelectExactlyOneMode() {
        for (String platform : List.of("OpenAI", "Glm")) {
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat(platform, file(MessageContent.FileSource.builder()
                    .fileId("file-1").fileData(PDF).filename("a.pdf").build())));
        }
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Glm", file(MessageContent.FileSource.builder().fileId("file-1").fileUrl(URL).build())));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Doubao", audio(MessageContent.AudioSource.builder().data("YQ==").url(URL).format("wav").build())));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Doubao", video(MessageContent.VideoSource.builder().fileId("file-1").url(URL).build())));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Anthropic", document(MessageContent.ContentSource.builder().type("url").url(URL).fileId("file-1").build())));
        for (String platform : List.of("OpenAI", "Doubao")) {
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> responses(platform, LlmResponsesContent.builder().type("input_image").imageUrl(URL).fileId("file-1").build()));
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> responses(platform, LlmResponsesContent.builder().type("input_file").fileUrl(URL).fileData("YQ==").filename("a.pdf").build()));
        }
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> responses("Doubao", LlmResponsesContent.builder().type("input_audio").audioUrl(URL).fileId("file-1").build()));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> responses("Doubao", LlmResponsesContent.builder().type("input_video").videoUrl(URL).fileId("file-1").build()));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> responses("Qwen", LlmResponsesContent.builder().type("input_audio").audioUrl(URL).data(AUDIO).format("wav").build()));
    }

    @Test
    void qwenFormatAndUnconfirmedGlmAnthropicSourcesAreRejected() {
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Qwen", audio(MessageContent.AudioSource.builder().data(AUDIO).build())));
        for (LlmResponsesContent block : List.of(LlmResponsesContent.builder().type("input_audio").audioUrl(URL).build(),
                LlmResponsesContent.builder().type("input_audio").data(AUDIO).build())) {
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> responses("Qwen", block));
        }
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Anthropic", MessageContent.builder().type(ContentTypeEnum.IMAGE)
                .source(MessageContent.ContentSource.builder().type("file").fileId("file-1").build()).build()));
        chat("Anthropic", MessageContent.builder().type(ContentTypeEnum.IMAGE).source(MessageContent.ContentSource.builder().type("url").url(URL).build()).build());
        chat("Anthropic", MessageContent.builder().type(ContentTypeEnum.IMAGE).source(MessageContent.ContentSource.builder()
                .type("base64").mediaType("image/png").data("YQ==").build()).build());
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Glm", video(MessageContent.VideoSource.builder().url(URL).fps(2.0).build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Glm", MessageContent.builder().type(ContentTypeEnum.VIDEO_URL)
                .videoUrl(MessageContent.VideoSource.builder().url(URL).build()).fps(2.0).build()));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Glm", video(MessageContent.VideoSource.builder().url("data:video/mp4;base64,YQ==").build())));
    }

    @Test
    void responsesAudioVideoRequireUserAndCannotAppearInToolOutput() {
        for (String platform : List.of("Doubao", "Qwen")) {
            for (LlmResponsesContent block : List.of(LlmResponsesContent.builder().type("input_audio").audioUrl(AUDIO).format("wav").build(),
                    LlmResponsesContent.builder().type("input_video").videoUrl(URL).build())) {
                responses(platform, block);
                for (String role : List.of("assistant", "system", "developer")) {
                    LlmResponsesRequest request = LlmResponsesRequest.builder().input(List.of(
                            LlmResponsesInput.builder().role(role).content(List.of(block)).build())).build();
                    error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> NativeInputUtil.responsesInput(request, platform));
                }
                LlmResponsesRequest output = LlmResponsesRequest.builder().input(List.of(LlmResponsesInput.builder()
                        .type("function_call_output").callId("call-1").outputContents(List.of(block)).build())).build();
                error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> NativeInputUtil.responsesInput(output, platform));
            }
        }
    }

    @Test
    void geminiCompatibilityAcceptsOnlyConfirmedTextImageAndBase64Audio() {
        chat("Gemini", MessageContent.builder().type(ContentTypeEnum.TEXT).text("hello").build());
        chat("Gemini", MessageContent.builder().type(ContentTypeEnum.IMAGE_URL).imageUrl(MessageContent.UrlSource.builder().url(URL).build()).build());
        for (String format : List.of("wav", "mp3")) chat("Gemini", audio(MessageContent.AudioSource.builder().data("YQ==").format(format).build()));
        for (String data : List.of(URL, AUDIO)) error(ErrorCodeEnum.PARAM_ILLEGAL,
                () -> chat("Gemini", audio(MessageContent.AudioSource.builder().data(data).format("wav").build())));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> chat("Gemini", audio(MessageContent.AudioSource.builder().data("YQ==").format("ogg").build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Gemini", audio(MessageContent.AudioSource.builder().url(URL).format("wav").build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Gemini", file(MessageContent.FileSource.builder().fileId("file-1").build())));
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> chat("Gemini", video(MessageContent.VideoSource.builder().url(URL).build())));
    }

    @ParameterizedTest
    @MethodSource("chatAdapters")
    void emptyTypedChatIsRejectedBeforeSupplier(LlmService adapter) {
        for (LlmRequest request : List.of(LlmRequest.builder().build(), LlmRequest.builder().messages(List.of()).build())) {
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> body(adapter, "buildRequestBody", request, false));
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> NativeInputUtil.chatMessages(request, "OpenAI", () -> fail("supplier called")));
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void emptyTypedResponsesAndUnknownTypesAreRejected(LlmService adapter) {
        for (LlmResponsesRequest request : List.of(LlmResponsesRequest.builder().build(), LlmResponsesRequest.builder().input(List.of()).build())) {
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> body(adapter, "buildResponsesRequestBody", request, false));
        }
        LlmResponsesRequest unknown = LlmResponsesRequest.builder().input(List.of(LlmResponsesInput.builder().role("user")
                .content(List.of(LlmResponsesContent.builder().type("invented_media").text("hello").build())).build())).build();
        error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> body(adapter, "buildResponsesRequestBody", unknown, false));
    }

    @Test
    void lowercaseChatEnumsAreAcceptedButUnknownEnumsAndUntypedBodiesAreNot() throws IOException {
        for (ContentTypeEnum type : ContentTypeEnum.values()) {
            assertEquals(type, MAPPER.readValue("{\"type\":\"" + type.getWireType() + "\"}", MessageContent.class).getType());
        }
        assertThrows(IOException.class, () -> MAPPER.readValue("{\"type\":\"invented_media\"}", MessageContent.class));
        assertThrows(IOException.class, () -> MAPPER.readValue("{\"input\":\"hello\"}", LlmResponsesRequest.class));
        assertThrows(IOException.class, () -> MAPPER.readValue("{\"input\":{\"body\":true}}", LlmResponsesRequest.class));
    }

    @Test
    void signedThinkingToolCallsAndMultimodalToolResultsReplay() throws IOException {
        List<Map<String, Object>> thinking = List.of(Map.of("type", "thinking", "thinking", "reason", "signature", "signed"),
                Map.of("type", "redacted_thinking", "data", "opaque"));
        LlmToolCall call = LlmToolCall.builder().id("call-1").name("lookup").arguments("{}")
                .extraContent(Map.of("google", Map.of("thought_signature", "signed"))).build();
        LlmMessage assistant = LlmMessage.ofToolCalls(List.of(call));
        assistant.setThinkingBlocks(thinking);
        LlmMessage result = LlmMessage.ofMultiModal("tool", List.of(MessageContent.ofText("result"),
                MessageContent.builder().type(ContentTypeEnum.IMAGE).source(MessageContent.ContentSource.builder().type("url").url(URL).build()).build(),
                document(MessageContent.ContentSource.builder().type("file").fileId("file-1").build())));
        result.setToolCallId("call-1");
        LlmRequest request = LlmRequest.builder().messages(List.of(assistant, result)).build();
        JsonNode original = MAPPER.valueToTree(request);
        JsonNode body = body(new AnthropicServiceImpl(null, null, null, null), "buildRequestBody", request, false);
        JsonNode blocks = body.path("messages").path(0).path("content");
        assertEquals(MAPPER.valueToTree(thinking.get(0)), blocks.path(0));
        assertEquals(MAPPER.valueToTree(thinking.get(1)), blocks.path(1));
        assertEquals("tool_use", blocks.path(2).path("type").asText());
        assertEquals("call-1", blocks.path(2).path("id").asText());
        JsonNode toolResult = body.path("messages").path(1).path("content").path(0);
        assertEquals("call-1", toolResult.path("tool_use_id").asText());
        assertEquals("image", toolResult.path("content").path(1).path("type").asText());
        assertEquals("file-1", toolResult.path("content").path(2).path("source").path("file_id").asText());
        assertEquals(original, MAPPER.valueToTree(request));
        List<LlmResponsesContent> output = List.of(text("result"), LlmResponsesContent.builder().type("input_image").imageUrl(URL).build(),
                LlmResponsesContent.builder().type("input_file").fileId("file-1").build());
        LlmResponsesInput item = LlmResponsesInput.builder().type("function_call_output").callId("call-1").outputContents(output).build();
        LlmResponsesRequest responses = LlmResponsesRequest.builder().input(List.of(item)).build();
        assertFalse(MAPPER.valueToTree(item).has("outputContents"));
        for (String platform : List.of("OpenAI", "Doubao")) {
            JsonNode wire = MAPPER.valueToTree(NativeInputUtil.responsesInput(responses, platform));
            assertEquals(MAPPER.valueToTree(output), wire.path(0).path("output"));
            assertEquals("call-1", wire.path(0).path("call_id").asText());
        }
        assertSame(output, item.getOutputContents());
        assertNull(item.getOutput());
    }

    @Test
    void openAiAssistantAudioReferenceAndMessageNameArePreserved() throws IOException {
        LlmMessage message = LlmMessage.builder().role("assistant").name("speaker")
                .audio(LlmMessage.AudioReference.builder().id("audio-1").build()).build();
        LlmRequest request = LlmRequest.builder().messages(List.of(message)).build();
        JsonNode original = MAPPER.valueToTree(request);
        JsonNode wire = body(new OpenAIServiceImpl(null, null, null, null), "buildRequestBody", request, false).path("messages").path(0);
        assertEquals("speaker", wire.path("name").asText());
        assertEquals("audio-1", wire.path("audio").path("id").asText());
        for (String provider : List.of("Gemini", "Deepseek", "Doubao", "Qwen", "Glm", "Qianfan", "Anthropic", "Moonshot", "MiniMax", "Mimo", "TokenHub", "Ollama")) {
            error(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED, () -> NativeInputUtil.chatMessages(request, provider, List::of));
        }
        assertEquals(original, MAPPER.valueToTree(request));
        for (String id : List.of("", " ")) {
            message.getAudio().setId(id);
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> NativeInputUtil.chatMessages(request, "OpenAI", List::of));
        }
        message.getAudio().setId("audio-1");
        for (String role : List.of("user", "system", "developer", "tool", "function")) {
            message.setRole(role);
            error(ErrorCodeEnum.PARAM_ILLEGAL, () -> NativeInputUtil.chatMessages(request, "OpenAI", List::of));
        }
    }

    @ParameterizedTest
    @MethodSource("responsesAdapters")
    void refusalResponseSerializesAndReplaysAsTypedInput(LlmService adapter) throws IOException {
        String json = "{\"id\":\"response-1\",\"status\":\"completed\",\"output\":[{\"id\":\"message-1\",\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"cannot comply\"},{\"type\":\"output_text\",\"text\":\"ordinary text\"}]}],\"usage\":{}}";
        LlmResponse response = ResponsesApiUtil.parseResponse(json, "model");
        LlmContentBlock refusal = response.getOutput().get(0).getContent().get(0);
        assertEquals("cannot comply", refusal.getText());
        assertEquals("cannot comply", refusal.getRefusal());
        JsonNode output = MAPPER.valueToTree(response).path("output");
        assertEquals(MAPPER.readTree(json).path("output"), output);
        assertFalse(output.path(0).path("content").path(0).has("text"));
        List<LlmResponsesInput> input = MAPPER.convertValue(output,
                MAPPER.getTypeFactory().constructCollectionType(List.class, LlmResponsesInput.class));
        LlmResponsesRequest request = LlmResponsesRequest.builder().model("model").input(input).build();
        for (boolean stream : List.of(false, true)) assertEquals(output, body(adapter, "buildResponsesRequestBody", request, stream).path("input"));
        assertEquals("cannot comply", MAPPER.readValue(MAPPER.writeValueAsString(refusal), LlmContentBlock.class).getText());
        assertEquals(MAPPER.valueToTree(Map.of("type", "refusal", "refusal", "legacy")),
                MAPPER.valueToTree(LlmContentBlock.builder().type("refusal").text("legacy").build()));
        error(ErrorCodeEnum.PARAM_ILLEGAL, () -> responses("OpenAI", LlmResponsesContent.builder().type("refusal").text("not native").build()));
        error(ErrorCodeEnum.LLM_RESPONSE_PARSE_FAILED, () -> ResponsesApiUtil.parseResponse("{\"output\":[{\"type\":\"web_search_call\",\"id\":\"ws-1\"}]}", "model"));
    }

    private static MessageContent file(MessageContent.FileSource source) {
        return MessageContent.builder().type(ContentTypeEnum.FILE).file(source).build();
    }

    private static MessageContent audio(MessageContent.AudioSource source) {
        return MessageContent.builder().type(ContentTypeEnum.INPUT_AUDIO).inputAudio(source).build();
    }

    private static MessageContent video(MessageContent.VideoSource source) {
        return MessageContent.builder().type(ContentTypeEnum.VIDEO_URL).videoUrl(source).build();
    }

    private static MessageContent document(MessageContent.ContentSource source) {
        return MessageContent.builder().type(ContentTypeEnum.DOCUMENT).source(source).build();
    }

    private static LlmResponsesContent text(String text) {
        return LlmResponsesContent.builder().type("input_text").text(text).build();
    }

    private static void chat(String platform, MessageContent block) {
        LlmRequest request = LlmRequest.builder().messages(List.of(LlmMessage.ofMultiModal("user", List.of(block)))).build();
        JsonNode original = MAPPER.valueToTree(request);
        Object wire = List.of(Map.of("role", "user", "content", "supplier result"));
        assertSame(wire, NativeInputUtil.chatMessages(request, platform, () -> wire));
        assertEquals(original, MAPPER.valueToTree(request));
    }

    private static void responses(String platform, LlmResponsesContent block) {
        LlmResponsesRequest request = LlmResponsesRequest.builder().input(List.of(LlmResponsesInput.builder().role("user").content(List.of(block)).build())).build();
        JsonNode original = MAPPER.valueToTree(request);
        assertEquals(MAPPER.valueToTree(request.getInput()), MAPPER.valueToTree(NativeInputUtil.responsesInput(request, platform)));
        assertEquals(original, MAPPER.valueToTree(request));
    }

    private static JsonNode body(LlmService adapter, String method, Object request, boolean stream) throws IOException {
        String json = ReflectionTestUtils.invokeMethod(adapter, method, request, stream);
        assertNotNull(json);
        return MAPPER.readTree(json);
    }

    private static void error(ErrorCodeEnum expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BizException.class, action).getErrorCode());
    }
}

