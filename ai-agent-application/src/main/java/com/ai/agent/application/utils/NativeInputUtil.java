package com.ai.agent.application.utils;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.util.*;
import java.util.function.Supplier;

/** 类型字段树的线级构建与平台校验；保留旧类名，不再接受原生主体透传。 */
public final class NativeInputUtil {

    private static final Set<String> CORE_KEYS = Set.of("model", "messages", "input", "stream");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    private NativeInputUtil() {}

    public static void validateChatRequest(LlmRequest request) {
        require(request.getMessages() != null && !request.getMessages().isEmpty());
        for (LlmMessage message : request.getMessages()) {
            require(message != null);
            String role = string(message.getRole());
            require(List.of("system", "developer", "user", "assistant", "tool", "function").contains(role));
            boolean history = "assistant".equals(role) && (message.getAudio() != null
                    || message.getToolCalls() != null && !message.getToolCalls().isEmpty()
                    || message.getThinkingBlocks() != null && !message.getThinkingBlocks().isEmpty()
                    || message.getReasoningContent() != null && !message.getReasoningContent().isBlank());
            require(message.getContents() != null && !message.getContents().isEmpty() || history);
            if (message.getContents() != null) {
                for (MessageContent content : message.getContents()) {
                    require(content != null && content.getType() != null);
                    if (content.getType() == com.ai.agent.application.enums.ContentTypeEnum.TEXT) {
                        require(textContent(content) != null);
                    }
                }
                if ("user".equals(role) && message.isTextOnly()) {
                    require(message.getContents().stream().anyMatch(c -> !textContent(c).isBlank()));
                }
            }
            if (message.getToolCalls() != null) require("assistant".equals(role));
            if ("tool".equals(role)) string(message.getToolCallId());
        }
    }

    @SuppressWarnings("unchecked")
    public static List<?> chatMessages(LlmRequest request, String platform, Supplier<?> fallbackSupplier) {
        validateChatRequest(request);
        String provider = platform.toLowerCase(Locale.ROOT);
        for (LlmMessage message : request.getMessages()) {
            if ("anthropic".equals(provider)) {
                require(List.of("system", "user", "assistant", "tool").contains(message.getRole()));
                if (message.getName() != null) unsupported();
                if ("system".equals(message.getRole()) && !message.isTextOnly()) unsupported();
            }
            if (message.getAudio() != null) {
                if (!"openai".equals(provider)) unsupported();
                require("assistant".equals(message.getRole()));
                string(message.getAudio().getId());
            }
            if (message.getContents() != null) for (MessageContent content : message.getContents()) {
                validateContentNodes(content);
                if (hasTypedSource(content)) buildContent(content, provider);
            }
        }
        List<?> messages = (List<?>) fallbackSupplier.get();
        if (!"anthropic".equals(provider)) {
            require(messages.size() == request.getMessages().size());
            for (int i = 0; i < messages.size(); i++) {
                LlmMessage source = request.getMessages().get(i);
                Map<String, Object> message = (Map<String, Object>) messages.get(i);
                if (source.getName() != null) message.put("name", source.getName());
                if (source.getAudio() != null) {
                    message.put("audio", Map.of("id", source.getAudio().getId()));
                    if (source.getContents() == null || source.getContents().isEmpty()) message.put("content", null);
                }
            }
        }
        return messages;
    }

    public static String textContent(MessageContent content) {
        return content.getValue() != null && !content.getValue().isEmpty() ? content.getValue() : content.getText();
    }

    private static boolean hasTypedSource(MessageContent content) {
        return content.getImageUrl() != null || content.getSource() != null
                || content.getType() != com.ai.agent.application.enums.ContentTypeEnum.TEXT
                && content.getType() != com.ai.agent.application.enums.ContentTypeEnum.IMAGE;
    }

    private static void validateContentNodes(MessageContent content) {
        String type = content.getType().getWireType();
        require(content.getImageUrl() == null || "image_url".equals(type));
        require(content.getInputAudio() == null || "input_audio".equals(type));
        require(content.getVideoUrl() == null || List.of("video", "video_url").contains(type));
        require(content.getFile() == null || "file".equals(type));
        require(content.getFileUrl() == null || "file_url".equals(type));
        require(content.getSource() == null || List.of("image", "document").contains(type));
        require(content.getFps() == null || List.of("video", "video_url").contains(type));
        require(content.getText() == null || "text".equals(type));
        require(content.getUri() == null && content.getData() == null && content.getMimeType() == null);
    }

    /** 仅组装类型对应的显式节点，不转换 URL 或数据语义。 */
    public static Map<String, Object> buildContent(MessageContent content, String platform) {
        require(content != null && content.getType() != null);
        validateContentNodes(content);
        String type = content.getType().getWireType();
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "video".equals(type) ? "video_url" : type);
        switch (type) {
            case "text" -> block.put("text", textContent(content));
            case "image_url" -> block.put("image_url", node(content.getImageUrl()));
            case "input_audio" -> block.put("input_audio", node(content.getInputAudio()));
            case "video", "video_url" -> {
                block.put("video_url", node(content.getVideoUrl()));
                if (content.getFps() != null) block.put("fps", content.getFps());
            }
            case "file" -> block.put("file", node(content.getFile()));
            case "file_url" -> block.put("file_url", node(content.getFileUrl()));
            case "image", "document" -> {
                if (!"anthropic".equalsIgnoreCase(platform)) unsupported();
                block.put("source", node(content.getSource()));
            }
            default -> unsupported();
        }
        chatContent(List.of(block), platform.toLowerCase(Locale.ROOT));
        return block;
    }

    private static Map<String, Object> node(Object source) {
        require(source != null);
        return MAPPER.convertValue(source, new TypeReference<Map<String, Object>>() {});
    }

    public static List<Map<String, Object>> responsesInput(LlmResponsesRequest request, String platform) {
        require(request.getInput() != null && !request.getInput().isEmpty());
        String provider = platform.toLowerCase(Locale.ROOT);
        if (!List.of("openai", "doubao", "qwen", "deepseek", "glm", "qianfan", "minimax", "mimo", "moonshot", "tokenhub", "ollama", "text").contains(provider)) unsupported();
        List<Map<String, Object>> input = new ArrayList<>();
        for (LlmResponsesInput source : request.getInput()) {
            require(source != null);
            require(source.getOutput() == null || source.getOutputContents() == null);
            Map<String, Object> item = node(source);
            if (source.getOutputContents() != null) {
                item.put("output", MAPPER.convertValue(source.getOutputContents(), new TypeReference<List<Map<String, Object>>>() {}));
            }
            input.add(item);
            String type = item.containsKey("type") ? string(item.get("type")) : "message";
            switch (type) {
                case "message" -> {
                    String role = string(item.get("role"));
                    require(List.of("system", "developer", "user", "assistant").contains(role));
                    Object content = item.get("content");
                    require(content instanceof List<?> && !((List<?>) content).isEmpty());
                    for (Object block : (List<?>) content) responsesBlock(object(block), provider, role);
                }
                case "reasoning", "function_call" -> { }
                case "function_call_output" -> {
                    Object output = item.get("output");
                    if (output instanceof List<?> blocks) {
                        for (Object block : blocks) responsesBlock(object(block), provider, "function_call_output");
                    } else require(output instanceof String);
                }
                default -> unsupported();
            }
        }
        return input;
    }

    public static void mergeExtraParams(Map<String, Object> body, Map<String, Object> extra) {
        if (extra != null) extra.forEach((key, value) -> putExtraParam(body, key, value));
    }

    public static void putExtraParam(Map<String, Object> body, String key, Object value) {
        if (!CORE_KEYS.contains(key) && !body.containsKey(key)) body.put(key, value);
    }

    private static void chatContent(Object content, String provider) {
        if (content instanceof String) return;
        require(content instanceof List<?>);
        for (Object value : (List<?>) content) {
            Map<?, ?> block = object(value);
            String type = string(block.get("type"));
            if ("anthropic".equals(provider)) {
                switch (type) {
                    case "text" -> require(block.get("text") instanceof String);
                    case "image", "document" -> anthropicSource(object(block.get("source")), "document".equals(type));
                    case "thinking", "redacted_thinking", "tool_use" -> { }
                    case "tool_result" -> {
                        Object result = block.get("content");
                        if (result != null) chatContent(result, provider);
                    }
                    default -> unsupported();
                }
                continue;
            }
            switch (type) {
                case "text" -> require(block.get("text") instanceof String);
                case "image_url" -> {
                    Map<?, ?> image = object(block.get("image_url"));
                    if (image.containsKey("file_id") || image.containsKey("data")) unsupported();
                    url(image.get("url"), true);
                }
                case "file" -> chatFile(object(block.get("file")), provider);
                case "file_url" -> {
                    if (!"glm".equals(provider)) unsupported();
                    url(object(block.get("file_url")).get("url"), false);
                }
                case "input_audio" -> chatAudio(object(block.get("input_audio")), provider);
                case "video_url" -> chatVideo(object(block.get("video_url")), block, provider);
                default -> unsupported();
            }
        }
    }

    private static void anthropicSource(Map<?, ?> source, boolean document) {
        String type = string(source.get("type"));
        if (!List.of("base64", "url", "file", "text").contains(type)
                || !document && List.of("file", "text").contains(type)) unsupported();
        oneSource(source, "data", "url", "file_id");
        switch (type) {
            case "base64" -> {
                String media = string(source.get("media_type"));
                require(document ? "application/pdf".equals(media)
                        : List.of("image/jpeg", "image/png", "image/gif", "image/webp").contains(media));
                base64(source.get("data"));
            }
            case "url" -> url(source.get("url"), false);
            case "file" -> string(source.get("file_id"));
            case "text" -> {
                if (!document) unsupported();
                require("text/plain".equals(string(source.get("media_type"))));
                string(source.get("data"));
            }
            default -> unsupported();
        }
    }

    private static void chatFile(Map<?, ?> file, String provider) {
        switch (provider) {
            case "openai" -> {
                if (file.containsKey("file_url")) unsupported();
                oneSource(file, "file_id", "file_data");
                if (file.containsKey("file_data")) string(file.get("filename"));
                if (file.containsKey("file_data")) dataUri(file.get("file_data"), "application/pdf");
            }
            case "doubao" -> {
                if (file.containsKey("file_url") || file.containsKey("file_data")) unsupported();
                oneSource(file, "file_id");
            }
            case "glm" -> {
                oneSource(file, "file_id", "file_url", "file_data");
                if (file.containsKey("file_data")) string(file.get("filename"));
                if (file.containsKey("file_url")) url(file.get("file_url"), false);
            }
            default -> unsupported();
        }
    }

    private static void chatAudio(Map<?, ?> audio, String provider) {
        switch (provider) {
            case "openai", "glm", "gemini" -> {
                if (audio.containsKey("url") || audio.containsKey("file_id")) unsupported();
                base64(audio.get("data"));
                require(List.of("wav", "mp3").contains(string(audio.get("format"))));
            }
            case "qwen" -> {
                if (audio.containsKey("file_id") || audio.containsKey("url")) unsupported();
                url(audio.get("data"), true);
                string(audio.get("format"));
            }
            case "doubao" -> {
                oneSource(audio, "file_id", "data", "url");
                if (audio.containsKey("data")) base64(audio.get("data"));
                if (audio.containsKey("url")) url(audio.get("url"), false);
                if (audio.containsKey("data") || audio.containsKey("url")) string(audio.get("format"));
            }
            default -> unsupported();
        }
    }

    private static void chatVideo(Map<?, ?> video, Map<?, ?> block, String provider) {
        switch (provider) {
            case "qwen" -> {
                if (video.containsKey("file_id") || video.containsKey("fps")) unsupported();
                url(video.get("url"), true);
                fps(block);
            }
            case "doubao" -> {
                oneSource(video, "file_id", "url");
                if (video.containsKey("url")) url(video.get("url"), true);
                if (block.containsKey("fps")) unsupported();
                fps(video);
            }
            case "glm" -> {
                if (video.containsKey("file_id") || video.containsKey("data")
                        || video.containsKey("fps") || block.containsKey("fps")) unsupported();
                url(video.get("url"), false);
            }
            default -> unsupported();
        }
    }

    private static void responsesBlock(Map<?, ?> block, String provider, String role) {
        String type = string(block.get("type"));
        if (List.of("input_audio", "input_video").contains(type) && !"user".equals(role)) unsupported();
        if ("function_call_output".equals(role)
                && !List.of("input_text", "input_image", "input_file").contains(type)) unsupported();
        switch (type) {
            case "input_text", "output_text", "reasoning_text", "summary_text" -> require(block.get("text") instanceof String);
            case "refusal" -> require(block.get("refusal") instanceof String);
            case "input_image" -> {
                if (!List.of("openai", "doubao", "qwen").contains(provider)) unsupported();
                if ("qwen".equals(provider) && block.containsKey("file_id")) unsupported();
                oneSource(block, "image_url", "file_id");
                if (block.containsKey("image_url")) url(block.get("image_url"), true);
            }
            case "input_file" -> {
                if (!List.of("openai", "doubao").contains(provider)) unsupported();
                oneSource(block, "file_id", "file_url", "file_data");
                if (block.containsKey("file_data")) string(block.get("filename"));
                if (block.containsKey("file_url")) url(block.get("file_url"), false);
            }
            case "input_audio" -> {
                if ("doubao".equals(provider)) {
                    oneSource(block, "file_id", "audio_url");
                    if (block.containsKey("audio_url")) url(block.get("audio_url"), true);
                    if (block.containsKey("data")) unsupported();
                } else if ("qwen".equals(provider)) {
                    if (block.containsKey("file_id")) unsupported();
                    oneSource(block, "audio_url", "data");
                    string(block.get("format"));
                    if (block.containsKey("audio_url")) url(block.get("audio_url"), true);
                    if (block.containsKey("data")) {
                        dataUri(block.get("data"), null);
                    }
                } else unsupported();
            }
            case "input_video" -> {
                if ("doubao".equals(provider)) oneSource(block, "file_id", "video_url");
                else if ("qwen".equals(provider)) {
                    if (block.containsKey("file_id")) unsupported();
                    string(block.get("video_url"));
                }
                else unsupported();
                if (block.containsKey("video_url")) url(block.get("video_url"), true);
                fps(block);
            }
            default -> unsupported();
        }
    }

    private static Map<?, ?> object(Object value) {
        require(value instanceof Map<?, ?>);
        return (Map<?, ?>) value;
    }

    private static String string(Object value) {
        require(value instanceof String && !((String) value).isBlank());
        return (String) value;
    }

    private static void oneSource(Map<?, ?> value, String... keys) {
        int count = 0;
        for (String key : keys) {
            if (value.containsKey(key)) {
                string(value.get(key));
                count++;
            }
        }
        require(count == 1);
    }

    private static void url(Object value, boolean allowData) {
        String text = string(value);
        if (text.startsWith("data:")) {
            if (!allowData) unsupported();
            dataUri(text, null);
        } else require(text.matches("https?://[^\\s]+"));
    }

    private static void dataUri(Object value, String media) {
        String text = string(value);
        int comma = text.indexOf(',');
        require(comma > 0 && text.startsWith(media == null ? "data:" : "data:" + media + ";")
                && text.substring(0, comma).endsWith(";base64"));
        base64(text.substring(comma + 1));
    }

    private static void base64(Object value) {
        String text = string(value);
        require(!text.startsWith("data:") && !text.startsWith("http"));
        try {
            Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
        }
    }

    private static void fps(Map<?, ?> value) {
        if (value.containsKey("fps")) require(value.get("fps") instanceof Number
                && ((Number) value.get("fps")).doubleValue() > 0);
    }

    private static void require(boolean condition) {
        if (!condition) throw new BizException(ErrorCodeEnum.PARAM_ILLEGAL);
    }

    private static void unsupported() {
        throw new BizException(ErrorCodeEnum.LLM_CONTENT_TYPE_NOT_SUPPORTED);
    }
}

