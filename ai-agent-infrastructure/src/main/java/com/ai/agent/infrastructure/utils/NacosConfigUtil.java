package com.ai.agent.infrastructure.utils;

import com.ai.agent.infrastructure.config.NacosConfig;
import com.ai.agent.infrastructure.config.NacosDataId;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @Description: Nacos 配置静态访问工具类，对标 Lion 使用方式。
 *               调用方通过静态方法 + {@link NacosDataId} 枚举 + key 精确获取配置，支持热更新（Nacos 变更后立即生效，无需重启）。
 *
 * <p>设计规则：
 * <ul>
 *   <li>dataId 必须使用 {@link NacosDataId} 枚举，禁止直接传字符串</li>
 *   <li>dataId 不存在于缓存（未配置或未加入索引）→ 返回 defaultValue + warn 日志（非必要配置场景）</li>
 *   <li>dataId 存在但 key 不存在 → 抛 {@link IllegalStateException} + error 日志（配了就要配对）</li>
 *   <li>value 类型转换失败 → 抛 {@link IllegalStateException} + error 日志</li>
 * </ul>
 *
 * <pre>
 * 使用示例：
 *   int maxRetries = NacosConfigUtil.getInt(NacosDataId.AI_AGENT_RETRY, "maxRetries", 3);
 *   long interval  = NacosConfigUtil.getLong(NacosDataId.AI_AGENT_RETRY, "interval", 1000L);
 * </pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: NacosConfigUtil
 * @Author: HUANGcong
 * @Date: Created in 2026/5/13
 * @Version: 2.0
 */
@Slf4j
@Component
public class NacosConfigUtil {

    /** 合法的 DataId 后缀白名单，决定解析方式 */
    private static final Set<String> VALID_SUFFIXES = Set.of(".json", ".properties", ".yaml", ".yml");

    private static NacosConfig nacosConfig;

    @Autowired
    public void setConfigCenter(NacosConfig nacosConfig) {
        NacosConfigUtil.nacosConfig = nacosConfig;
    }

    // ==================== 基本类型 ====================

    /**
     * 获取字符串配置
     *
     * @param dataId       目标 DataId 枚举
     * @param key          配置 key
     * @param defaultValue dataId 不存在于缓存时的兜底默认值
     */
    public static String getString(NacosDataId dataId, String key, String defaultValue) {
        String value = getRaw(dataId, key, defaultValue);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取 int 配置
     */
    public static int getInt(NacosDataId dataId, String key, int defaultValue) {
        String value = getRaw(dataId, key, defaultValue);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值 [%s] 无法转换为 int", dataId.dataId(), key, value);
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 获取 long 配置
     */
    public static long getLong(NacosDataId dataId, String key, long defaultValue) {
        String value = getRaw(dataId, key, defaultValue);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值 [%s] 无法转换为 long", dataId.dataId(), key, value);
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 获取 double 配置
     */
    public static double getDouble(NacosDataId dataId, String key, double defaultValue) {
        String value = getRaw(dataId, key, defaultValue);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值 [%s] 无法转换为 double", dataId.dataId(), key, value);
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 获取 boolean 配置（"true"/"false" 不区分大小写）
     */
    public static boolean getBoolean(NacosDataId dataId, String key, boolean defaultValue) {
        String value = getRaw(dataId, key, defaultValue);
        if (value == null) return defaultValue;
        String trimmed = value.trim();
        if ("true".equalsIgnoreCase(trimmed)) return true;
        if ("false".equalsIgnoreCase(trimmed)) return false;
        String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值 [%s] 无法转换为 boolean", dataId.dataId(), key, value);
        log.error(msg);
        throw new IllegalStateException(msg);
    }

    // ==================== 复杂类型（JSON） ====================

    /**
     * 获取 JSON 对象配置
     *
     * @param dataId 目标 DataId 枚举
     * @param key    配置 key，对应值须为合法 JSON 对象字符串
     * @param clazz  目标类型
     * @param <T>    泛型
     * @return 解析后的对象；dataId 不存在时返回 null
     */
    public static <T> T getObject(NacosDataId dataId, String key, Class<T> clazz) {
        String value = getRaw(dataId, key, null);
        if (value == null) return null;
        try {
            return JsonUtil.readValue(value, clazz);
        } catch (Exception e) {
            String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值无法转换为 %s", dataId.dataId(), key, clazz.getSimpleName());
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 获取泛型对象配置（适用于嵌套泛型，如 {@code Map<String, List<String>>}）
     */
    public static <T> T getObject(NacosDataId dataId, String key, TypeReference<T> typeRef) {
        String value = getRaw(dataId, key, null);
        if (value == null) return null;
        try {
            return JsonUtil.readValue(value, typeRef);
        } catch (Exception e) {
            String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值无法转换为目标泛型类型", dataId.dataId(), key);
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 获取 JSON 数组配置
     *
     * @param dataId      目标 DataId 枚举
     * @param key         配置 key，对应值须为合法 JSON 数组字符串
     * @param elementType 集合元素类型
     * @param <T>         泛型
     * @return 解析后的列表；dataId 不存在时返回空列表
     */
    public static <T> List<T> getList(NacosDataId dataId, String key, Class<T> elementType) {
        String value = getRaw(dataId, key, Collections.emptyList());
        if (value == null) return Collections.emptyList();
        try {
            return JsonUtil.readList(value, elementType);
        } catch (Exception e) {
            String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值无法转换为 List<%s>", dataId.dataId(), key, elementType.getSimpleName());
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 获取 JSON Map 配置
     *
     * @param dataId   目标 DataId 枚举
     * @param key      配置 key，对应值须为合法 JSON 对象字符串
     * @param keyClass Map key 类型
     * @param valClass Map value 类型
     * @param <K>      key 泛型
     * @param <V>      value 泛型
     * @return 解析后的 Map；dataId 不存在时返回空 Map
     */
    public static <K, V> Map<K, V> getMap(NacosDataId dataId, String key, Class<K> keyClass, Class<V> valClass) {
        String value = getRaw(dataId, key, Collections.emptyMap());
        if (value == null) return Collections.emptyMap();
        try {
            return JsonUtil.readValue(value, new TypeReference<Map<K, V>>() {});
        } catch (Exception e) {
            String msg = String.format("[NacosConfigUtil] dataId=%s key=%s 的值无法转换为 Map<%s,%s>",
                    dataId.dataId(), key, keyClass.getSimpleName(), valClass.getSimpleName());
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * 获取 JSON Set 配置
     *
     * @param dataId      目标 DataId 枚举
     * @param key         配置 key，对应值须为合法 JSON 数组字符串
     * @param elementType Set 元素类型
     * @param <T>         泛型
     * @return 解析后的 Set；dataId 不存在时返回空 Set
     */
    public static <T> Set<T> getSet(NacosDataId dataId, String key, Class<T> elementType) {
        List<T> list = getList(dataId, key, elementType);
        return list.isEmpty() ? Collections.emptySet() : Set.copyOf(list);
    }

    // ==================== 内部方法 ====================

    /**
     * 核心读取方法，按 dataId + key 精确查找。
     *
     * @param dataId       目标 DataId 枚举
     * @param key          目标 key
     * @param defaultValue dataId 不存在时的默认值（仅用于日志）
     * @return 找到的原始字符串值；dataId 不存在时返回 null；dataId 存在但 key 不存在时抛异常
     * @throws IllegalArgumentException dataId 的 dataId() 字符串后缀不合法
     * @throws IllegalStateException    dataId 存在但 key 不存在
     */
    private static String getRaw(NacosDataId dataId, String key, Object defaultValue) {
        // 1. 校验 dataId 后缀（白名单校验，防止枚举值填错）
        String dataIdStr = dataId.dataId();
        boolean validSuffix = VALID_SUFFIXES.stream().anyMatch(dataIdStr::endsWith);
        if (!validSuffix) {
            throw new IllegalArgumentException(
                    String.format("[NacosConfigUtil] dataId [%s] 后缀不合法，合法后缀: %s", dataIdStr, VALID_SUFFIXES));
        }

        // 2. NacosConfig 未初始化（极端情况，如单元测试环境）
        if (nacosConfig == null) {
            log.warn("[NacosConfigUtil] NacosConfig 未初始化，dataId={}，key={}，返回默认值={}", dataIdStr, key, defaultValue);
            return null;
        }

        // 3. 按 dataId 精确查
        String raw = nacosConfig.getRaw(dataIdStr, key);

        // 3a. dataId 不存在于缓存 → 非必要配置，warn 并走默认值
        if (raw == null) {
            log.warn("[NacosConfigUtil] dataId 不存在于缓存（未配置或未加入索引），dataId={}，key={}，返回默认值={}", dataIdStr, key, defaultValue);
            return null;
        }

        // 3b. dataId 存在但 key 不存在 → 配置错误，必须感知
        if (NacosConfig.KEY_NOT_FOUND.equals(raw)) {
            String msg = String.format("[NacosConfigUtil] dataId=%s 存在但 key=%s 不存在，请检查 Nacos 配置", dataIdStr, key);
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        return raw;
    }
}

