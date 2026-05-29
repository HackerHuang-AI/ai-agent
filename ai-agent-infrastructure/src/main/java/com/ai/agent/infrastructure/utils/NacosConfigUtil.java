package com.ai.agent.infrastructure.utils;

import com.ai.agent.infrastructure.config.NacosConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * @Description: Nacos 配置静态访问工具类，对标 Lion 使用方式。
 *               调用方通过静态方法 + key 直接获取配置，支持热更新（Nacos 变更后立即生效，无需重启）。
 *               所有方法均支持 defaultValue 兜底，Nacos 未配置或解析失败时返回默认值。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: NacosConfigUtil
 * @Author: HUANGcong
 * @Date: Created in 2026/5/13
 * @Version: 1.0
 */
@Slf4j
@Component
public class NacosConfigUtil {

    private static NacosConfig nacosConfig;

    @Autowired
    public void setConfigCenter(NacosConfig nacosConfig) {
        NacosConfigUtil.nacosConfig = nacosConfig;
    }

    // ==================== 基本类型 ====================

    public static String getString(String key, String defaultValue) {
        String value = getRaw(key);
        return value != null ? value : defaultValue;
    }

    public static int getInt(String key, int defaultValue) {
        String value = getRaw(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("[NacosConfigUtil] key={} 转换 int 失败，值={}，返回默认值={}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public static long getLong(String key, long defaultValue) {
        String value = getRaw(key);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            log.warn("[NacosConfigUtil] key={} 转换 long 失败，值={}，返回默认值={}", key, value, defaultValue);
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String value = getRaw(key);
        if (value == null) return defaultValue;
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) return true;
        if ("false".equalsIgnoreCase(trimmed)) return false;
        log.warn("[NacosConfigUtil] key={} 转换 boolean 失败，值={}，返回默认值={}", key, value, defaultValue);
        return defaultValue;
    }

    // ==================== 复杂类型（JSON） ====================

    public static <T> T getObject(String key, Class<T> clazz) {
        String value = getRaw(key);
        if (value == null) return null;
        try {
            return JsonUtil.readValue(value, clazz);
        } catch (Exception e) {
            log.warn("[NacosConfigUtil] key={} 转换 {} 失败，值={}，error={}", key, clazz.getSimpleName(), value, e.getMessage());
            return null;
        }
    }

    public static <T> List<T> getList(String key, Class<T> elementType) {
        String value = getRaw(key);
        if (value == null) return Collections.emptyList();
        try {
            return JsonUtil.readList(value, elementType);
        } catch (Exception e) {
            log.warn("[NacosConfigUtil] key={} 转换 List<{}> 失败，值={}，error={}", key, elementType.getSimpleName(), value, e.getMessage());
            return Collections.emptyList();
        }
    }

    public static <T> T getObject(String key, TypeReference<T> typeRef) {
        String value = getRaw(key);
        if (value == null) return null;
        try {
            return JsonUtil.readValue(value, typeRef);
        } catch (Exception e) {
            log.warn("[NacosConfigUtil] key={} 转换泛型类型失败，值={}，error={}", key, value, e.getMessage());
            return null;
        }
    }

    // ==================== 内部方法 ====================

    private static String getRaw(String key) {
        if (nacosConfig == null) {
            log.warn("[NacosConfigUtil] NacosConfig 未初始化，key={}", key);
            return null;
        }
        return nacosConfig.getRaw(key);
    }
}

