package com.ai.agent.starter.controller;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.ai.agent.application.service.LlmRouter;
import com.ai.agent.application.service.LlmService;
import com.ai.agent.application.service.impl.AnthropicServiceImpl;
import com.ai.agent.application.service.impl.DoubaoServiceImpl;
import com.ai.agent.application.service.impl.OpenAIServiceImpl;
import com.ai.agent.application.utils.ResponsesStreamUtil.CancellableConsumer;
import com.ai.agent.client.dto.LlmFacadeResponsesInput;
import com.ai.agent.client.dto.LlmToolCallDto;
import com.ai.agent.infrastructure.utils.JsonUtil;
import com.ai.agent.starter.vo.LlmRequestVO;
import com.ai.agent.starter.vo.LlmResponsesRequestVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LlmChatControllerProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_CALLS = """
            [{"id":"call-1","name":"lookup","arguments":"{}","extra_content":{"google":{"thought_signature":"signed"}}}]
            """.trim();
    private static final String THINKING = """
            [{"type":"thinking","thinking":"reason","signature":"signed","unknown":{"kept":true}},
             {"type":"redacted_thinking","data":"opaque"}]
            """.replace("\n", "").trim();
    private static final String CHAT_REQUEST = """
            {"platform":"OpenAI","apiKey":"key","endpoint":"https://example.invalid/chat","modelCode":"model",
             "temperature":0.5,"topP":0.8,"topK":4,"frequencyPenalty":0.2,"maxTokens":123,
             "tools":[{"type":"function","function":{"name":"lookup","parameters":{"type":"object"}}}],
             "toolChoice":"auto","extraParams":{"reasoning_effort":"high"},
             "messages":[
               {"role":"assistant","value":"answer","reasoningContent":"reason","thinkingBlocks":%s,
                "toolCalls":[{"id":"call-1","name":"lookup","arguments":"{}",
                              "extraContent":{"google":{"thought_signature":"signed"},"vendor":{"kept":true}}}]},
               {"role":"tool","toolCallId":"call-1","value":"result","reasoningContent":"tool reason","thinkingBlocks":%s},
               {"role":"assistant","value":"plain","reasoningContent":"plain reason","thinkingBlocks":%s}]}
            """.formatted(THINKING, THINKING, THINKING);
    private static final String TYPED_MESSAGES = """
            [{"role":"user","name":"speaker","contents":[
              {"type":"text","text":"hello"},
              {"type":"image_url","imageUrl":{"url":"https://example.invalid/image","detail":"high"}},
              {"type":"input_audio","inputAudio":{"data":"YQ==","format":"wav"}},
              {"type":"input_audio","inputAudio":{"url":"https://example.invalid/audio","format":"mp3"}},
              {"type":"input_audio","inputAudio":{"fileId":"audio-1"}},
              {"type":"video_url","videoUrl":{"url":"https://example.invalid/video"},"fps":2},
              {"type":"video_url","videoUrl":{"fileId":"video-1","fps":3}},
              {"type":"file","file":{"fileId":"file-1"}},
              {"type":"file","file":{"fileUrl":"https://example.invalid/file"}},
              {"type":"file","file":{"fileData":"data:application/pdf;base64,YQ==","filename":"a.pdf"}},
              {"type":"file_url","fileUrl":{"url":"https://example.invalid/file"}},
              {"type":"document","source":{"type":"base64","mediaType":"application/pdf","data":"YQ=="}},
              {"type":"document","source":{"type":"url","url":"https://example.invalid/document"}},
              {"type":"document","source":{"type":"file","fileId":"document-1"}},
              {"type":"audio","uri":"gs://bucket/audio","data":"YQ==","mimeType":"audio/wav"}]},
             {"role":"assistant","name":"model","audio":{"id":"audio-reference-1"}}]
            """;
    private static final String RESPONSES_REQUEST = """
            {"platform":"OpenAI","apiKey":"key","endpoint":"https://example.invalid/responses","model":"model",
             "input":[{"type":"reasoning","id":"reason-1","status":"completed","encrypted_content":"opaque",
                       "summary":[{"type":"summary_text","text":"summary","unknown":{"kept":true}}]},
                      {"type":"function_call_output","call_id":"call-1","output":"result"}],
             "instructions":"instructions","temperature":0.5,"topP":0.8,"maxOutputTokens":123,
             "tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}],
             "toolChoice":"required","extraParams":{"previous_response_id":"previous","store":false}}
            """;

    @BeforeEach
    void initJsonUtil() {
        JsonUtil.init(MAPPER);
    }

    static Stream<Class<?>> controllers() {
        return Stream.of(LlmChatController.class, DeepseekChatController.class, AnthropicChatController.class,
                GeminiChatController.class, DoubaoChatController.class, OpenAIChatController.class,
                GlmChatController.class, QwenChatController.class, MoonshotChatController.class,
                MiniMaxChatController.class, OllamaChatController.class, QianfanChatController.class,
                TokenHubChatController.class, MimoChatController.class);
    }

    static Stream<Class<?>> modelControllers() {
        return Stream.of(LlmChatController.class, DoubaoChatController.class,
                OllamaChatController.class, QianfanChatController.class);
    }

    @ParameterizedTest
    @MethodSource("modelControllers")
    void modelPaginationUsesVoDefaultsAndExplicitValues(Class<?> controllerType) throws Exception {
        Constructor<?> constructor = controllerType.getConstructors()[0];
        Object dependency = mock(constructor.getParameterTypes()[0]);
        LlmModelPage page = LlmModelPage.of(List.of(), 1, 10);
        if (dependency instanceof LlmRouter router) {
            when(router.listModels(eq("OpenAI"), any(), anyInt(), anyInt())).thenReturn(page);
        } else {
            when(((LlmService) dependency).listModels(any(), anyInt(), anyInt())).thenReturn(page);
        }
        MockMvc mvc = MockMvcBuilders.standaloneSetup(constructor.newInstance(dependency)).build();
        for (String body : List.of("{\"platform\":\"OpenAI\"}",
                "{\"platform\":\"OpenAI\",\"pageNo\":2,\"pageSize\":3}", "")) {
            if (body.isEmpty() && dependency instanceof LlmRouter) continue;
            clearInvocations(dependency);
            MvcResult result = mvc.perform(post(path(controllerType) + "/models")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn();
            assertEquals(MAPPER.readTree("{\"records\":[],\"total\":0,\"pageNo\":1,\"pageSize\":10,\"hasNext\":false}"),
                    MAPPER.readTree(result.getResponse().getContentAsString()).path("data"));
            int pageNo = body.contains("pageNo") ? 2 : 1;
            int pageSize = body.contains("pageSize") ? 3 : 10;
            if (dependency instanceof LlmRouter router) {
                verify(router).listModels("OpenAI", null, pageNo, pageSize);
            } else {
                verify((LlmService) dependency).listModels(null, pageNo, pageSize);
            }
            verifyNoMoreInteractions(dependency);
        }
    }

    @ParameterizedTest
    @MethodSource("modelControllers")
    void inheritedPaginationValidationRejectsNullAndOutOfRangeValues(Class<?> controllerType) throws Exception {
        Constructor<?> constructor = controllerType.getConstructors()[0];
        Object dependency = mock(constructor.getParameterTypes()[0]);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(constructor.newInstance(dependency)).build();
        for (String json : List.of("{\"pageNo\":null}", "{\"pageSize\":null}", "{\"pageNo\":0}",
                "{\"pageNo\":-1}", "{\"pageSize\":0}", "{\"pageSize\":101}")) {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(json);
            body.put("platform", "OpenAI");
            mvc.perform(post(path(controllerType) + "/models").contentType(MediaType.APPLICATION_JSON)
                            .content(MAPPER.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(dependency);
    }

    @Test
    void modelPageInheritancePreservesSlicingAndBoundaryBehavior() {
        List<LlmModelInfo> models = List.of(LlmModelInfo.builder().id("a").build(),
                LlmModelInfo.builder().id("b").build(), LlmModelInfo.builder().id("c").build());
        LlmModelPage second = LlmModelPage.of(models, 2, 2);
        assertEquals(models.subList(2, 3), second.getRecords());
        assertEquals(3, second.getTotal());
        assertFalse(second.getHasNext());
        assertTrue(LlmModelPage.of(models, 1, 2).getHasNext());
        assertTrue(LlmModelPage.of(models, Integer.MAX_VALUE, 100).getRecords().isEmpty());
        assertEquals(0, LlmModelPage.of(null, 1, 10).getTotal());
    }

    @ParameterizedTest(name = "{0} chat SSE protocol")
    @MethodSource("controllers")
    void allControllerPathsEmitTypedChatEventsAndPreserveRequestFields(Class<?> controllerType) throws Exception {
        Constructor<?> constructor = controllerType.getConstructors()[0];
        Object dependency = mock(constructor.getParameterTypes()[0]);
        Object controller = constructor.newInstance(dependency);
        List<String> chunks = new ArrayList<>(List.of("[TOOL_CALLS]" + TOOL_CALLS,
                "[REASONING]reason", "[THINKING_BLOCKS]" + THINKING, "answer"));
        for (String text : List.of("[REASONING]literal", "[THINKING_BLOCKS]literal", "[TEXT]literal",
                "[TEXT][TEXT]literal", "plain [REASONING]literal")) chunks.add(LlmService.escapeText(text));
        chunks.add(null);
        if (dependency instanceof LlmRouter router) {
            doAnswer(invocation -> {
                emit(invocation.getArgument(2), chunks);
                return null;
            }).when(router).chatStream(eq("OpenAI"), any(), any());
        } else {
            doAnswer(invocation -> {
                emit(invocation.getArgument(1), chunks);
                return null;
            }).when((LlmService) dependency).chatStream(any(), any());
        }
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String body = streamBody(mvc, path(controllerType) + "/chat/stream", CHAT_REQUEST);
        assertEquals(List.of(new Event("tool_calls", TOOL_CALLS), new Event("reasoning", "reason"),
                new Event("thinking_blocks", THINKING), new Event("chunk", "answer"),
                new Event("chunk", "[REASONING]literal"), new Event("chunk", "[THINKING_BLOCKS]literal"),
                new Event("chunk", "[TEXT]literal"), new Event("chunk", "[TEXT][TEXT]literal"),
                new Event("chunk", "plain [REASONING]literal"), new Event("done", "[DONE]")), events(body));
        List<LlmToolCallDto> clientCalls = MAPPER.readValue(events(body).get(0).payload(),
                MAPPER.getTypeFactory().constructCollectionType(List.class, LlmToolCallDto.class));
        assertEquals("call-1", clientCalls.get(0).getId());
        assertEquals("lookup", clientCalls.get(0).getName());
        assertEquals("{}", clientCalls.get(0).getArguments());
        assertEquals(Map.of("google", Map.of("thought_signature", "signed")), clientCalls.get(0).getExtraContent());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        if (dependency instanceof LlmRouter router) {
            verify(router).chatStream(eq("OpenAI"), captured.capture(), any());
        } else {
            verify((LlmService) dependency).chatStream(captured.capture(), any());
        }
        assertChatRequest(captured.getValue());
        verifyNoMoreInteractions(dependency);
    }

    @ParameterizedTest
    @MethodSource("controllers")
    void allControllerSyncPathsPreserveThinkingAndToolSignatures(Class<?> controllerType) throws Exception {
        Constructor<?> constructor = controllerType.getConstructors()[0];
        Object dependency = mock(constructor.getParameterTypes()[0]);
        Object controller = constructor.newInstance(dependency);
        List<Map<String, Object>> blocks = MAPPER.readValue(CHAT_REQUEST, LlmRequestVO.class)
                .getMessages().get(0).getThinkingBlocks();
        LlmToolCall call = LlmToolCall.builder().id("call-1").name("lookup").arguments("{}")
                .extraContent(Map.of("google", Map.of("thought_signature", "signed"))).build();
        LlmResponse response = LlmResponse.builder().modelCode("model").choices(List.of(LlmChoice.builder()
                .content("answer").reasoningContent("reason").thinkingBlocks(blocks).toolCalls(List.of(call)).build())).build();
        if (dependency instanceof LlmRouter router) {
            when(router.chat(eq("OpenAI"), any())).thenReturn(response);
        } else {
            when(((LlmService) dependency).chat(any())).thenReturn(response);
        }
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MvcResult result = mvc.perform(post(path(controllerType) + "/chat").contentType(MediaType.APPLICATION_JSON)
                .content(CHAT_REQUEST)).andExpect(status().isOk()).andReturn();
        JsonNode choice = MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("choices").path(0);
        assertEquals("answer", choice.path("content").asText());
        assertEquals("reason", choice.path("reasoningContent").asText());
        assertEquals(MAPPER.valueToTree(blocks), choice.path("thinkingBlocks"));
        assertEquals(MAPPER.valueToTree(call.getExtraContent()), choice.path("toolCalls").path(0).path("extraContent"));
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        if (dependency instanceof LlmRouter router) {
            verify(router).chat(eq("OpenAI"), captured.capture());
        } else {
            verify((LlmService) dependency).chat(captured.capture());
        }
        assertChatRequest(captured.getValue());
        verifyNoMoreInteractions(dependency);
    }

    @ParameterizedTest
    @MethodSource("controllers")
    void allChatConversionsKeepThinkingOnlyContentsNullAndLegacyFallbackUnchanged(Class<?> controllerType) throws Exception {
        Object controller = controllerType.getConstructors()[0].newInstance(new Object[]{null});
        for (String value : List.of("null", "\"\"", "\"answer\"")) {
            LlmRequestVO vo = MAPPER.readValue("{\"messages\":[{\"role\":\"assistant\",\"value\":" + value
                    + ",\"thinkingBlocks\":" + THINKING + "}]}", LlmRequestVO.class);
            LlmRequest request = LlmChatController.toServiceRequest(vo);
            LlmMessage message = request.getMessages().get(0);
            assertEquals(vo.getMessages().get(0).getThinkingBlocks(), message.getThinkingBlocks());
            if ("null".equals(value)) assertNull(message.getContents());
            else assertEquals(vo.getMessages().get(0).getValue(), message.getTextContent());
        }
        for (String thinking : List.of("null", "[]")) {
            LlmRequest request = LlmChatController.toServiceRequest(
                    MAPPER.readValue("{\"messages\":[{\"role\":\"assistant\",\"thinkingBlocks\":" + thinking + "}]}", LlmRequestVO.class));
            assertNull(request.getMessages().get(0).getContents());
        }
    }

    @ParameterizedTest
    @MethodSource("controllers")
    void typedMessagesReachAllChatSyncAndStreamPathsUnchanged(Class<?> controllerType) throws Exception {
        Constructor<?> constructor = controllerType.getConstructors()[0];
        Object dependency = mock(constructor.getParameterTypes()[0]);
        Object controller = constructor.newInstance(dependency);
        List<LlmRequest> captured = new ArrayList<>();
        if (dependency instanceof LlmRouter router) {
            when(router.chat(eq("OpenAI"), any())).thenAnswer(invocation -> {
                captured.add(invocation.getArgument(1));
                return LlmResponse.builder().build();
            });
            doAnswer(invocation -> {
                captured.add(invocation.getArgument(1));
                ((Consumer<String>) invocation.getArgument(2)).accept(null);
                return null;
            }).when(router).chatStream(eq("OpenAI"), any(), any());
        } else {
            when(((LlmService) dependency).chat(any())).thenAnswer(invocation -> {
                captured.add(invocation.getArgument(0));
                return LlmResponse.builder().build();
            });
            doAnswer(invocation -> {
                captured.add(invocation.getArgument(0));
                ((Consumer<String>) invocation.getArgument(1)).accept(null);
                return null;
            }).when((LlmService) dependency).chatStream(any(), any());
        }
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(CHAT_REQUEST);
        body.set("messages", MAPPER.readTree(TYPED_MESSAGES));
        body.putArray("interactionSteps");
        String json = MAPPER.writeValueAsString(body);
        LlmRequestVO vo = MAPPER.readValue(json, LlmRequestVO.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post(path(controllerType) + "/chat").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        streamBody(mvc, path(controllerType) + "/chat/stream", json);
        assertEquals(2, captured.size());
        for (LlmRequest request : captured) {
            assertEquals(2, request.getMessages().size());
            var source = vo.getMessages().get(0);
            LlmMessage message = request.getMessages().get(0);
            assertEquals(source.getName(), message.getName());
            for (int i = 0; i < source.getContents().size(); i++) {
                MessageContent expected = new MessageContent();
                org.springframework.beans.BeanUtils.copyProperties(source.getContents().get(i), expected);
                assertEquals(expected, message.getContents().get(i));
                assertEquals(source.getContents().get(i).getType(), message.getContents().get(i).getType());
            }
            assertEquals(vo.getMessages().get(1).getAudio(), request.getMessages().get(1).getAudio());
            assertNull(request.getMessages().get(1).getContents());
            assertEquals(vo.getModelCode(), request.getModelCode());
            assertEquals(vo.getTools(), request.getTools());
            assertEquals(vo.getExtraParams(), request.getExtraParams());
        }
    }

    @ParameterizedTest
    @MethodSource("controllers")
    void allChatHttpPathsRejectMissingEmptyAndUnknownEnumBodies(Class<?> controllerType) throws Exception {
        Constructor<?> constructor = controllerType.getConstructors()[0];
        Object dependency = mock(constructor.getParameterTypes()[0]);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(constructor.newInstance(dependency)).build();
        for (String fields : List.of("", ",\"messages\":null", ",\"messages\":[]",
                ",\"interactionSteps\":[{\"type\":\"thought\",\"signature\":\"sig\"}]",
                ",\"messages\":[],\"interactionSteps\":[{\"type\":\"thought\",\"signature\":\"sig\"}]",
                ",\"messages\":[{\"role\":\" \"}]",
                ",\"messages\":[{\"role\":\"user\",\"contents\":[{\"type\":\"invented_media\",\"text\":\"hello\"}]}]")) {
            for (String suffix : List.of("/chat", "/chat/stream")) {
                mvc.perform(post(path(controllerType) + suffix).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"platform\":\"OpenAI\"" + fields + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(dependency);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"hello\"}]}]",
            "[{\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"imageUrl\":\"https://example.invalid/image\",\"detail\":\"high\"},{\"type\":\"input_file\",\"fileId\":\"file-1\"},{\"type\":\"input_file\",\"fileUrl\":\"https://example.invalid/file\"},{\"type\":\"input_file\",\"fileData\":\"YQ==\",\"filename\":\"a.pdf\"}]}]",
            "[{\"role\":\"user\",\"content\":[{\"type\":\"input_audio\",\"audioUrl\":\"https://example.invalid/audio\",\"format\":\"wav\"},{\"type\":\"input_audio\",\"data\":\"data:audio/wav;base64,YQ==\",\"format\":\"wav\"},{\"type\":\"input_video\",\"videoUrl\":\"https://example.invalid/video\",\"fps\":2},{\"type\":\"input_video\",\"fileId\":\"video-1\"}]}]",
            "[{\"role\":\"assistant\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"cannot comply\"},{\"type\":\"output_text\",\"text\":\"answer\"}]}]"})
    void typedInputReachesResponsesSyncAndStreamUnchanged(String input) throws Exception {
        LlmRouter router = mock(LlmRouter.class);
        when(router.responses(eq("OpenAI"), any())).thenReturn(LlmResponse.builder().build());
        doAnswer(invocation -> {
            ((Consumer<String>) invocation.getArgument(2)).accept(null);
            return null;
        }).when(router).responsesStream(eq("OpenAI"), any(), any());
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(RESPONSES_REQUEST);
        body.set("input", MAPPER.readTree(input));
        String json = MAPPER.writeValueAsString(body);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmChatController(router)).build();
        mvc.perform(post("/api/llm/responses").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        streamBody(mvc, "/api/llm/responses/stream", json);
        ArgumentCaptor<LlmResponsesRequest> captured = ArgumentCaptor.forClass(LlmResponsesRequest.class);
        verify(router).responses(eq("OpenAI"), captured.capture());
        verify(router).responsesStream(eq("OpenAI"), captured.capture(), any());
        for (LlmResponsesRequest request : captured.getAllValues()) {
            assertEquals(MAPPER.readValue(json, LlmResponsesRequestVO.class).getInput(), request.getInput());
            assertEquals("instructions", request.getInstructions());
            assertEquals(123, request.getMaxOutputTokens());
            assertEquals(MAPPER.readValue(json, LlmResponsesRequestVO.class).getExtraParams(), request.getExtraParams());
        }
        DoubaoServiceImpl service = mock(DoubaoServiceImpl.class);
        when(service.responses(any())).thenReturn(LlmResponse.builder().build());
        MockMvcBuilders.standaloneSetup(new DoubaoChatController(service)).build()
                .perform(post("/api/doubao/responses").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
        verify(service).responses(captured.capture());
        assertEquals(MAPPER.readValue(json, LlmResponsesRequestVO.class).getInput(), captured.getValue().getInput());
    }

    @Test
    void responsesHttpPathsRejectMissingEmptyAndUntypedBodies() throws Exception {
        LlmRouter router = mock(LlmRouter.class);
        DoubaoServiceImpl service = mock(DoubaoServiceImpl.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmChatController(router), new DoubaoChatController(service)).build();
        for (String fields : List.of("", ",\"input\":null", ",\"input\":[]",
                ",\"input\":{}", ",\"input\":\"hello\"", ",\"input\":[\"not an item\"]")) {
            for (String path : List.of("/api/llm/responses", "/api/llm/responses/stream", "/api/doubao/responses")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"platform\":\"OpenAI\"" + fields + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(router, service);
    }

    @Test
    void claudeDedicatedAndUnifiedHttpThinkingOnlyReplayUsesRealRequestBuilder() throws Exception {
        for (Class<?> controllerType : List.of(AnthropicChatController.class, LlmChatController.class)) {
            AnthropicServiceImpl service = mock(AnthropicServiceImpl.class);
            Object controller = controllerType == AnthropicChatController.class ? new AnthropicChatController(service)
                    : new LlmChatController(new LlmRouter(Map.of("anthropicServiceImpl", service)));
            List<LlmRequest> captured = new ArrayList<>();
            when(service.chat(any())).thenAnswer(invocation -> {
                captured.add(invocation.getArgument(0));
                return LlmResponse.builder().build();
            });
            doAnswer(invocation -> {
                captured.add(invocation.getArgument(0));
                Consumer<String> consumer = invocation.getArgument(1);
                consumer.accept(null);
                return null;
            }).when(service).chatStream(any(), any());
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
            for (String value : List.of("", ",\"value\":null")) {
                String json = "{\"platform\":\"Anthropic\",\"modelCode\":\"model\",\"messages\":[{\"role\":\"assistant\""
                        + value + ",\"thinkingBlocks\":" + THINKING + "}]}";
                mvc.perform(post(path(controllerType) + "/chat").contentType(MediaType.APPLICATION_JSON).content(json))
                        .andExpect(status().isOk());
                assertEquals(List.of(new Event("done", "[DONE]")),
                        events(streamBody(mvc, path(controllerType) + "/chat/stream", json)));
            }
            assertEquals(4, captured.size());
            AnthropicServiceImpl realService = new AnthropicServiceImpl(null, null, null, null);
            for (LlmRequest request : captured) {
                assertNull(request.getMessages().get(0).getContents());
                for (boolean stream : List.of(false, true)) {
                    String body = ReflectionTestUtils.invokeMethod(realService, "buildRequestBody", request, stream);
                    assertEquals(MAPPER.readTree(THINKING), MAPPER.readTree(body).path("messages").path(0).path("content"));
                    assertFalse(body.contains("\"text\":null"));
                }
            }
        }
    }

    @Test
    void thinkingWithRealMultimodalContentsDoesNotTakeNullFallback() throws Exception {
        for (Class<?> controllerType : List.of(LlmChatController.class, DoubaoChatController.class)) {
            Object controller = controllerType.getConstructors()[0].newInstance(new Object[]{null});
            LlmRequestVO vo = MAPPER.readValue("{\"messages\":[{\"role\":\"assistant\",\"thinkingBlocks\":" + THINKING
                    + ",\"contents\":[{\"type\":\"TEXT\",\"value\":\"answer\"},{\"type\":\"IMAGE\",\"value\":\"https://example.com/image.png\"}]}]}",
                    LlmRequestVO.class);
            LlmRequest request = LlmChatController.toServiceRequest(vo);
            assertEquals(List.of(MessageContent.ofText("answer"), MessageContent.ofImage("https://example.com/image.png")),
                    request.getMessages().get(0).getContents());
            String body = ReflectionTestUtils.invokeMethod(new AnthropicServiceImpl(null, null, null, null),
                    "buildRequestBody", request, false);
            JsonNode content = MAPPER.readTree(body).path("messages").path(0).path("content");
            assertEquals(MAPPER.readTree(THINKING).get(0), content.get(0));
            assertEquals(MAPPER.readTree(THINKING).get(1), content.get(1));
            assertEquals("answer", content.path(2).path("text").asText());
            assertEquals("image", content.path(3).path("type").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"response.completed", "response.incomplete"})
    void responsesSemanticEventsKeepTheirNamesAndPayloadsUntilTerminal(String terminal) throws Exception {
        LlmRouter router = mock(LlmRouter.class);
        String itemDone = "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call-1\"}}";
        String delta = "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"error response.failed\"}";
        String terminalEvent = "{\"type\":\"" + terminal + "\",\"response\":{\"id\":\"response-1\",\"status\":\""
                + terminal.substring("response.".length()) + "\"}}";
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(itemDone);
            consumer.accept(delta);
            consumer.accept(terminalEvent);
            consumer.accept(null);
            return null;
        }).when(router).responsesStream(eq("OpenAI"), any(), any());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmChatController(router)).build();
        List<Event> actual = events(streamBody(mvc, path(LlmChatController.class) + "/responses/stream", RESPONSES_REQUEST));
        assertEquals(List.of(new Event("response.output_item.done", itemDone),
                new Event("response.function_call_arguments.delta", delta), new Event(terminal, terminalEvent)), actual);
        ArgumentCaptor<LlmResponsesRequest> captured = ArgumentCaptor.forClass(LlmResponsesRequest.class);
        verify(router).responsesStream(eq("OpenAI"), captured.capture(), any());
        assertResponsesRequest(captured.getValue());
        verifyNoMoreInteractions(router);
    }

    @ParameterizedTest
    @ValueSource(strings = {"response.failed", "error"})
    void responsesFailureIsForwardedButNeverEmitsDone(String type) throws Exception {
        LlmRouter router = mock(LlmRouter.class);
        String failure = "{\"type\":\"" + type + "\",\"error\":{\"message\":\"failed\"}}";
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept(failure);
            consumer.accept("[ERROR]");
            consumer.accept("{\"type\":\"response.completed\"}");
            consumer.accept(null);
            consumer.accept(null);
            return null;
        }).when(router).responsesStream(eq("OpenAI"), any(), any());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmChatController(router)).build();
        assertEquals(List.of(new Event(type, failure)),
                events(streamBody(mvc, path(LlmChatController.class) + "/responses/stream", RESPONSES_REQUEST)));
    }

    @Test
    void responsesInternalErrorSentinelIsAnErrorEventNotDone() throws Exception {
        LlmRouter router = mock(LlmRouter.class);
        doAnswer(invocation -> {
            Consumer<String> consumer = invocation.getArgument(2);
            consumer.accept("[ERROR]");
            consumer.accept("[ERROR]");
            consumer.accept("{\"type\":\"response.completed\"}");
            consumer.accept(null);
            return null;
        }).when(router).responsesStream(eq("OpenAI"), any(), any());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmChatController(router)).build();
        List<Event> actual = events(streamBody(mvc, path(LlmChatController.class) + "/responses/stream", RESPONSES_REQUEST));
        assertEquals(1, actual.size());
        assertEquals("error", actual.get(0).name());
        JsonNode payload = MAPPER.readTree(actual.get(0).payload());
        assertEquals("error", payload.path("type").asText());
        assertEquals("2002004", payload.path("code").asText());
        assertEquals("LLM call failed", payload.path("message").asText());
    }

    @Test
    void genericAndDoubaoResponsesHttpMappingPreservesAllCommonAndReasoningFields() throws Exception {
        for (Class<?> controllerType : List.of(LlmChatController.class, DoubaoChatController.class)) {
            Constructor<?> constructor = controllerType.getConstructors()[0];
            Object dependency = mock(constructor.getParameterTypes()[0]);
            Object controller = constructor.newInstance(dependency);
            LlmResponse response = LlmResponse.builder().output(List.of(LlmOutputItem.builder()
                    .id("reason-1").type("reasoning").callId("call-1").encryptedContent("opaque")
                    .content(List.of(LlmContentBlock.builder().type("reasoning_text").text("reason").build())).build())).build();
            if (dependency instanceof LlmRouter router) {
                when(router.responses(eq("OpenAI"), any())).thenReturn(response);
            } else {
                when(((LlmService) dependency).responses(any())).thenReturn(response);
            }
            MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
            MvcResult result = mvc.perform(post(path(controllerType) + "/responses").contentType(MediaType.APPLICATION_JSON)
                    .content(RESPONSES_REQUEST)).andExpect(status().isOk()).andReturn();
            JsonNode output = MAPPER.readTree(result.getResponse().getContentAsString()).path("data").path("output").path(0);
            assertEquals("opaque", output.path("encryptedContent").asText());
            assertEquals("reason", output.path("content").path(0).path("text").asText());
            String outputJson = MAPPER.writeValueAsString(output);
            LlmFacadeResponsesInput clientHistory = MAPPER.readValue(outputJson, LlmFacadeResponsesInput.class);
            assertEquals("opaque", clientHistory.getEncryptedContent());
            assertEquals("call-1", clientHistory.getCallId());
            LlmResponsesInput history = MAPPER.readValue(outputJson, LlmResponsesInput.class);
            assertFalse(output.path("content").get(0).has("url"));
            assertEquals("opaque", history.getEncryptedContent());
            assertEquals("call-1", history.getCallId());
            JsonNode historyJson = MAPPER.valueToTree(history);
            assertEquals("opaque", historyJson.path("encrypted_content").asText());
            assertEquals("call-1", historyJson.path("call_id").asText());
            assertFalse(historyJson.has("encryptedContent"));
            assertFalse(historyJson.has("callId"));
            ArgumentCaptor<LlmResponsesRequest> captured = ArgumentCaptor.forClass(LlmResponsesRequest.class);
            if (dependency instanceof LlmRouter router) {
                verify(router).responses(eq("OpenAI"), captured.capture());
            } else {
                verify((LlmService) dependency).responses(captured.capture());
            }
            assertResponsesRequest(captured.getValue());
            var replay = (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(RESPONSES_REQUEST);
            replay.set("input", MAPPER.createArrayNode().add(output));
            mvc.perform(post(path(controllerType) + "/responses").contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsString(replay))).andExpect(status().isOk());
            if (dependency instanceof LlmRouter router) {
                verify(router, times(2)).responses(eq("OpenAI"), captured.capture());
            } else {
                verify((LlmService) dependency, times(2)).responses(captured.capture());
            }
            assertEquals(List.of(history), captured.getValue().getInput());
            String body = ReflectionTestUtils.invokeMethod(mock(OpenAIServiceImpl.class, CALLS_REAL_METHODS),
                    "buildResponsesRequestBody", captured.getValue(), false);
            JsonNode official = MAPPER.readTree(body).path("input").get(0);
            assertEquals(historyJson, official);
            assertEquals("reason", official.path("content").get(0).path("text").asText());
            assertFalse(body.contains("\"url\":null"));
            assertFalse(body.contains("\"image_url\":null"));
            assertFalse(body.contains("\"detail\":null"));
            verifyNoMoreInteractions(dependency);
        }
    }

    @Test
    void rawGeminiToolJsonCanBePostedBackWithoutLosingSignature() throws Exception {
        List<LlmToolCall> calls = MAPPER.readValue(TOOL_CALLS,
                MAPPER.getTypeFactory().constructCollectionType(List.class, LlmToolCall.class));
        assertEquals(Map.of("google", Map.of("thought_signature", "signed")), calls.get(0).getExtraContent());
        JsonNode serialized = MAPPER.valueToTree(calls);
        assertTrue(serialized.path(0).has("extraContent"));
        assertFalse(serialized.path(0).has("extra_content"));
        assertEquals(calls, MAPPER.readValue(MAPPER.writeValueAsString(calls),
                MAPPER.getTypeFactory().constructCollectionType(List.class, LlmToolCall.class)));
        LlmRouter router = mock(LlmRouter.class);
        when(router.chat(eq("OpenAI"), any())).thenReturn(LlmResponse.builder().build());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmChatController(router)).build();
        String request = "{\"platform\":\"OpenAI\",\"messages\":[{\"role\":\"assistant\",\"toolCalls\":" + TOOL_CALLS + "}]}";
        mvc.perform(post("/api/llm/chat").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        ArgumentCaptor<LlmRequest> captured = ArgumentCaptor.forClass(LlmRequest.class);
        verify(router).chat(eq("OpenAI"), captured.capture());
        assertEquals(calls, captured.getValue().getMessages().get(0).getToolCalls());
    }

    @ParameterizedTest
    @ValueSource(strings = {"onError", "onComplete", "onTimeout"})
    void responsesHttpLifecycleCancelsWrapperAndSuppressesLateEvents(String lifecycle) throws Exception {
        LlmRouter router = mock(LlmRouter.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmChatController(router)).build();
        MvcResult initial = mvc.perform(post(path(LlmChatController.class) + "/responses/stream")
                        .contentType(MediaType.APPLICATION_JSON).content(RESPONSES_REQUEST))
                .andExpect(request().asyncStarted()).andReturn();
        ArgumentCaptor<Consumer<String>> captured = ArgumentCaptor.forClass(Consumer.class);
        verify(router).responsesStream(eq("OpenAI"), any(), captured.capture());
        CancellableConsumer consumer = assertInstanceOf(CancellableConsumer.class, captured.getValue());
        String delta = "{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}";
        assertFalse(consumer.isCancelled());
        consumer.accept(delta);
        assertEquals(List.of(new Event("response.output_text.delta", delta)),
                events(initial.getResponse().getContentAsString(StandardCharsets.UTF_8)));
        MockAsyncContext context = assertInstanceOf(MockAsyncContext.class, initial.getRequest().getAsyncContext());
        assertFalse(context.getListeners().isEmpty());
        AsyncEvent event = new AsyncEvent(context, initial.getRequest(), initial.getResponse(),
                new IOException("client disconnected"));
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
            consumer.accept("{\"type\":\"response.completed\"}");
            consumer.accept(null);
        });
        assertEquals(body, initial.getResponse().getContentAsString(StandardCharsets.UTF_8));
        verifyNoMoreInteractions(router);
    }

    @ParameterizedTest
    @ValueSource(strings = {"send", "malformed"})
    void responsesConsumerRethrowsIOExceptionAndIgnoresLateCallbacks(String failure) throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        IOException disconnected = new IOException("disconnected");
        if ("send".equals(failure)) doThrow(disconnected).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        Consumer<String> downstream = ReflectionTestUtils.invokeMethod(new LlmChatController(mock(LlmRouter.class)),
                "buildResponsesSseConsumer", emitter, "OpenAI", new AtomicBoolean());
        CancellableConsumer consumer = new CancellableConsumer(downstream);
        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> consumer.accept("send".equals(failure) ? "{\"type\":\"response.output_text.delta\"}" : "{broken}"));
        if ("send".equals(failure)) assertSame(disconnected, error.getCause());
        assertTrue(consumer.isCancelled());
        consumer.accept("[ERROR]");
        consumer.accept("{\"type\":\"response.completed\"}");
        consumer.accept(null);
        verify(emitter).completeWithError(error.getCause());
        if ("send".equals(failure)) verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verifyNoMoreInteractions(emitter);
    }

    @Test
    void responsesLifecycleCancellationDuringSendDoesNotCompleteWithError() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean terminated = new AtomicBoolean();
        Consumer<String> downstream = ReflectionTestUtils.invokeMethod(new LlmChatController(mock(LlmRouter.class)),
                "buildResponsesSseConsumer", emitter, "OpenAI", terminated);
        CancellableConsumer consumer = new CancellableConsumer(downstream);
        IOException disconnected = new IOException("disconnected");
        doAnswer(invocation -> {
            assertFalse(Thread.holdsLock(consumer));
            terminated.set(true);
            consumer.cancel();
            throw disconnected;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        UncheckedIOException error = assertThrows(UncheckedIOException.class,
                () -> consumer.accept("{\"type\":\"response.output_text.delta\"}"));
        assertSame(disconnected, error.getCause());
        assertTrue(terminated.get());
        assertTrue(consumer.isCancelled());
        assertDoesNotThrow(() -> {
            consumer.accept("{\"type\":\"response.output_text.delta\"}");
            consumer.accept("[ERROR]");
            consumer.accept("{broken}");
            consumer.accept("{\"type\":\"response.completed\"}");
            consumer.accept(null);
        });
        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).completeWithError(any());
        verifyNoMoreInteractions(emitter);
    }

    @Test
    void responsesConsumerCompletesOnlyOnceOnNull() {
        SseEmitter emitter = mock(SseEmitter.class);
        Consumer<String> consumer = ReflectionTestUtils.invokeMethod(new LlmChatController(mock(LlmRouter.class)),
                "buildResponsesSseConsumer", emitter, "OpenAI", new AtomicBoolean());
        consumer.accept(null);
        consumer.accept(null);
        consumer.accept("[ERROR]");
        verify(emitter).complete();
        verifyNoMoreInteractions(emitter);
    }

    @Test
    void unsupportedResponsesPropagatesBusinessErrorForSyncAndStream() throws Exception {
        LlmService service = mock(LlmService.class, CALLS_REAL_METHODS);
        LlmChatController controller = new LlmChatController(new LlmRouter(Map.of("openAIServiceImpl", service)));
        LlmResponsesRequestVO request = MAPPER.readValue(RESPONSES_REQUEST, LlmResponsesRequestVO.class);
        assertEquals("2002012", ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED.getCode());
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED,
                assertThrows(BizException.class, () -> controller.responses(request)).getErrorCode());
        assertEquals(ErrorCodeEnum.LLM_PROTOCOL_NOT_SUPPORTED,
                assertThrows(BizException.class, () -> controller.responsesStream(request)).getErrorCode());
        verify(service).responses(any());
        verify(service).responsesStream(any(), any());
        verifyNoMoreInteractions(service);
    }

    private static void assertChatRequest(LlmRequest request) throws Exception {
        LlmRequestVO vo = MAPPER.readValue(CHAT_REQUEST, LlmRequestVO.class);
        assertEquals(vo.getApiKey(), request.getApiKey());
        assertEquals(vo.getEndpoint(), request.getEndpoint());
        assertEquals(vo.getModelCode(), request.getModelCode());
        assertEquals(vo.getTemperature(), request.getTemperature());
        assertEquals(vo.getTopP(), request.getTopP());
        assertEquals(vo.getTopK(), request.getTopK());
        assertEquals(vo.getFrequencyPenalty(), request.getFrequencyPenalty());
        assertEquals(vo.getMaxTokens(), request.getMaxTokens());
        assertEquals(vo.getTools(), request.getTools());
        assertEquals(vo.getToolChoice(), request.getToolChoice());
        assertEquals(vo.getExtraParams(), request.getExtraParams());
        assertEquals(3, request.getMessages().size());
        for (int i = 0; i < vo.getMessages().size(); i++) {
            var input = vo.getMessages().get(i);
            LlmMessage message = request.getMessages().get(i);
            assertEquals(input.getRole(), message.getRole());
            assertEquals(input.getValue(), message.getTextContent());
            assertEquals(input.getReasoningContent(), message.getReasoningContent());
            assertEquals(input.getThinkingBlocks(), message.getThinkingBlocks());
            assertEquals(input.getToolCalls(), message.getToolCalls());
            assertEquals(input.getToolCallId(), message.getToolCallId());
        }
    }

    private static void assertResponsesRequest(LlmResponsesRequest request) throws Exception {
        LlmResponsesRequestVO vo = MAPPER.readValue(RESPONSES_REQUEST, LlmResponsesRequestVO.class);
        assertEquals(vo.getApiKey(), request.getApiKey());
        assertEquals(vo.getEndpoint(), request.getEndpoint());
        assertEquals(vo.getModel(), request.getModel());
        assertEquals(vo.getInput(), request.getInput());
        assertEquals(vo.getInstructions(), request.getInstructions());
        assertEquals(vo.getTemperature(), request.getTemperature());
        assertEquals(vo.getTopP(), request.getTopP());
        assertEquals(vo.getMaxOutputTokens(), request.getMaxOutputTokens());
        assertEquals(vo.getTools(), request.getTools());
        assertEquals(vo.getToolChoice(), request.getToolChoice());
        assertEquals(vo.getExtraParams(), request.getExtraParams());
    }

    private static void emit(Consumer<String> consumer, List<String> chunks) {
        chunks.forEach(consumer);
    }

    private static String path(Class<?> controllerType) {
        return controllerType.getAnnotation(RequestMapping.class).value()[0];
    }

    private static String streamBody(MockMvc mvc, String path, String json) throws Exception {
        MvcResult initial = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted()).andReturn();
        initial.getAsyncResult(1000);
        MvcResult result = mvc.perform(asyncDispatch(initial)).andExpect(status().isOk()).andReturn();
        assertTrue(result.getResponse().getContentType().startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<Event> events(String body) {
        List<Event> events = new ArrayList<>();
        for (String frame : body.replace("\r\n", "\n").split("\n\n")) {
            if (frame.isBlank()) continue;
            String name = null;
            List<String> data = new ArrayList<>();
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) name = line.substring("event:".length()).stripLeading();
                if (line.startsWith("data:")) data.add(line.substring("data:".length()).stripLeading());
            }
            assertNotNull(name, frame);
            assertFalse(data.isEmpty(), frame);
            events.add(new Event(name, String.join("\n", data)));
        }
        return events;
    }

    private record Event(String name, String payload) {}
}

