package com.ai.agent.infrastructure.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * @Description: JSON 工具类，封装 Jackson 常用反序列化操作，作为 OkHttpUtil 的配套解析工具。
 *               使用方式：在 Spring 容器启动后通过 {@link #init(ObjectMapper)} 注入 Spring Boot 默认 ObjectMapper，
 *               之后所有地方均可通过静态方法调用，避免到处注入 ObjectMapper。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: JsonUtil
 * @Author: HUANGcong
 * @Date: Created in 2026/4/14
 * @Version: 1.0
 */
public class JsonUtil {

    private static ObjectMapper mapper;

    /**
     * 由 Spring 容器调用，注入默认 ObjectMapper。
     * 在 JacksonConfig 中完成初始化。
     */
    public static void init(ObjectMapper objectMapper) {
        JsonUtil.mapper = objectMapper;
    }

    // ==================== 反序列化 ====================

    public static <T> T readValue(String json, Class<T> clazz) throws IOException {
        return mapper.readValue(json, clazz);
    }

    public static <T> T readValue(String json, TypeReference<T> typeRef) throws IOException {
        return mapper.readValue(json, typeRef);
    }

    public static <T> List<T> readList(String json, Class<T> targetType) throws IOException {
        return mapper.readValue(json,
                mapper.getTypeFactory().constructCollectionType(List.class, targetType));
    }

    public static JsonNode readTree(String json) throws IOException {
        return mapper.readTree(json);
    }

    // ==================== 序列化 ====================

    public static String toJson(Object obj) throws IOException {
        return mapper.writeValueAsString(obj);
    }

    public static String toPrettyJson(Object obj) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
    }
}

