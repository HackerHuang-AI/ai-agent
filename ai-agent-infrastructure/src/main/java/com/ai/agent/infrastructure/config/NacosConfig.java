package com.ai.agent.infrastructure.config;

import com.ai.agent.infrastructure.enums.NacosDataIdEnum;
import com.ai.agent.infrastructure.utils.JsonUtil;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * @Description: Nacos 配置中心，支持索引 DataId 机制动态管理多配置文件热更新。
 *
 * <pre>
 * 设计模式（类比 Lion ZooKeeper Watch 父节点）：
 *   1. 固定订阅一个"索引 DataId"（nacos.index-data-id，默认 ai-agent-index.properties）
 *   2. 索引文件中用 dataIds= 列出所有业务 DataId
 *   3. 启动时订阅索引 + 索引中所有 DataId
 *   4. 索引变更时自动 diff，增量订阅新增 DataId，移除已删除 DataId 的缓存
 *   5. 业务 DataId 变更时全量替换对应分桶缓存（保证 key 删除不残留）
 * </pre>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.config
 * @ClassName: NacosConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/5/13
 * @Version: 2.0
 */
@Slf4j
@Component
public class NacosConfig {

    @Value("${spring.cloud.nacos.config.server-addr:}")
    private String serverAddr;

    @Value("${spring.application.name}")
    private String group;

    @Value("${nacos.index-data-id:ai-agent-index.properties}")
    private String indexDataId;

    private final Map<String, Map<String, String>> dataIdCache = new ConcurrentHashMap<>();
    private final Map<String, String> rawContentCache = new ConcurrentHashMap<>();
    private final Set<String> registeredDataIds = ConcurrentHashMap.newKeySet();

    /** 合法的 DataId 后缀白名单，决定解析方式 */
    private static final Set<String> VALID_SUFFIXES = Set.of(".json", ".properties", ".yaml", ".yml");

    private ConfigService configService;

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @PostConstruct
    public void init() {
        if (serverAddr == null || serverAddr.isBlank()) {
            log.warn("[NacosConfig] spring.cloud.nacos.config.server-addr 未配置，跳过 Nacos 初始化");
            return;
        }
        try {
            Properties nacosProperties = new Properties();
            nacosProperties.put("serverAddr", serverAddr);
            nacosProperties.put("namespace", "");
            nacosProperties.put("appName", group);
            configService = NacosFactory.createConfigService(nacosProperties);
        } catch (NacosException e) {
            log.error("[NacosConfig] 创建 ConfigService 失败，serverAddr={}，error={}", serverAddr, e.getMessage(), e);
            return;
        }
        listenIndexDataId();
    }

    private void listenIndexDataId() {
        try {
            String content = configService.getConfig(indexDataId, group, 5000);
            if (content != null && !content.isBlank()) {
                Set<String> dataIds = parseIndexContent(content);
                dataIds.forEach(this::listenBusinessDataId);
            } else {
                log.warn("[NacosConfig] 索引 DataId 内容为空，dataId={}，业务 DataId 暂无订阅", indexDataId);
            }

            configService.addListener(indexDataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String newContent) {
                    log.info("[NacosConfig] 索引 DataId 变更，开始 diff，dataId={}", indexDataId);
                    onIndexChanged(newContent);
                }
            });

            log.info("[NacosConfig] 索引 DataId 订阅成功，dataId={}, group={}", indexDataId, group);
        } catch (NacosException e) {
            log.error("[NacosConfig] 订阅索引 DataId 失败，dataId={}，error={}", indexDataId, e.getMessage(), e);
        }
    }

    private void onIndexChanged(String newContent) {
        if (newContent == null || newContent.isBlank()) {
            log.warn("[NacosConfig] 索引 DataId 内容变为空，跳过 diff");
            return;
        }

        Set<String> newDataIds = parseIndexContent(newContent);

        for (String dataId : newDataIds) {
            if (!registeredDataIds.contains(dataId)) {
                log.info("[NacosConfig] 索引新增 DataId，开始订阅，dataId={}", dataId);
                listenBusinessDataId(dataId);
            }
        }

        Set<String> toRemove = new HashSet<>(registeredDataIds);
        toRemove.removeAll(newDataIds);
        for (String dataId : toRemove) {
            log.info("[NacosConfig] 索引移除 DataId，清除缓存，dataId={}", dataId);
            dataIdCache.remove(dataId);
            rawContentCache.remove(dataId);
            registeredDataIds.remove(dataId);
        }
    }

    private void listenBusinessDataId(String dataId) {
        if (!registeredDataIds.add(dataId)) {
            return;
        }
        try {
            String content = configService.getConfig(dataId, group, 5000);
            if (content != null && !content.isBlank()) {
                updateCache(dataId, content);
            } else {
                log.warn("[NacosConfig] 业务 DataId 内容为空，dataId={}，跳过缓存写入", dataId);
            }

            configService.addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String newContent) {
                    log.info("[NacosConfig] 收到业务配置变更推送，dataId={}, group={}", dataId, group);
                    if (newContent == null || newContent.isBlank()) {
                        log.warn("[NacosConfig] 变更内容为空，清除缓存分桶，dataId={}", dataId);
                        dataIdCache.remove(dataId);
                        rawContentCache.remove(dataId);
                        return;
                    }
                    updateCache(dataId, newContent);
                }
            });

            log.info("[NacosConfig] 业务 DataId 订阅成功，dataId={}, group={}", dataId, group);
        } catch (NacosException e) {
            registeredDataIds.remove(dataId);
            log.error("[NacosConfig] 订阅业务 DataId 失败，dataId={}，error={}", dataId, e.getMessage(), e);
        }
    }

    private Set<String> parseIndexContent(String content) {
        try {
            Properties props = new Properties();
            props.load(new StringReader(content));
            return props.stringPropertyNames();
        } catch (Exception e) {
            log.error("[NacosConfig] 解析索引文件失败，error={}", e.getMessage(), e);
            return Set.of();
        }
    }

    private void updateCache(String dataId, String content) {
        try {
            Map<String, String> parsed;
            if (dataId.endsWith(".json")) {
                parsed = parseJson(dataId, content);
            } else if (dataId.endsWith(".yaml") || dataId.endsWith(".yml")) {
                parsed = parseYaml(dataId, content);
            } else {
                parsed = parseProperties(content);
            }
            dataIdCache.put(dataId, parsed);
            rawContentCache.put(dataId, content);
            log.info("[NacosConfig] 缓存更新完成，dataId={}，本次解析 {} 个 key", dataId, parsed.size());
        } catch (Exception e) {
            log.error("[NacosConfig] 解析配置失败，dataId={}，error={}", dataId, e.getMessage(), e);
        }
    }

    private Map<String, String> parseProperties(String content) throws Exception {
        Properties props = new Properties();
        props.load(new StringReader(content));
        Map<String, String> result = new HashMap<>();
        props.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    private Map<String, String> parseJson(String dataIdForLog, String content) throws Exception {
        JsonNode root = JSON_MAPPER.readTree(content);
        if (!root.isObject()) {
            log.warn("[NacosConfig] JSON 格式的根节点非 Object，以 dataId 为 key 存储，dataId={}", dataIdForLog);
            Map<String, String> result = new HashMap<>();
            result.put(dataIdForLog, content);
            return result;
        }
        Map<String, String> result = new HashMap<>();
        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isValueNode()) {
                result.put(entry.getKey(), value.asText());
            } else {
                result.put(entry.getKey(), value.toString());
            }
        });
        return result;
    }

    private Map<String, String> parseYaml(String dataId, String content) throws Exception {
        JsonNode root = YAML_MAPPER.readTree(content);
        if (!root.isObject()) {
            log.warn("[NacosConfig] YAML 格式的根节点非 Object，以 dataId 为 key 存储，dataId={}", dataId);
            Map<String, String> result = new HashMap<>();
            result.put(dataId, JSON_MAPPER.writeValueAsString(root));
            return result;
        }
        Map<String, String> result = new HashMap<>();
        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isValueNode()) {
                result.put(entry.getKey(), value.asText());
            } else {
                try {
                    result.put(entry.getKey(), JSON_MAPPER.writeValueAsString(value));
                } catch (Exception e) {
                    log.error("[NacosConfig] YAML key={} 序列化失败", entry.getKey(), e);
                }
            }
        });
        return result;
    }

    /**
     * 向指定 DataId 注册额外的配置变更监听器。
     * 监听器会在 Nacos 推送变更时回调，此时本实例缓存已更新完毕，可直接调用读取。
     *
     * @param dataId   目标 DataId（需带后缀）
     * @param listener Nacos Listener 实现
     */
    public void addListener(String dataId, Listener listener) {
        if (configService == null) {
            log.warn("[NacosConfig] ConfigService 未初始化，无法注册 listener，dataId={}", dataId);
            return;
        }
        try {
            configService.addListener(dataId, group, listener);
            log.info("[NacosConfig] 外部 listener 注册成功，dataId={}", dataId);
        } catch (NacosException e) {
            log.error("[NacosConfig] 外部 listener 注册失败，dataId={}，error={}", dataId, e.getMessage(), e);
        }
    }

    public Map<String, String> getCacheByDataId(String dataId) {
        return dataIdCache.get(dataId);
    }

    /**
     * 获取指定 DataId 的原始内容字符串（未解析的原文）。
     *
     * @param dataId 目标 DataId（需带后缀，如 ai-agent-llm.json）
     * @return 原始内容字符串；DataId 不存在于缓存时返回 null
     */
    public String getRawContent(String dataId) {
        return rawContentCache.get(dataId);
    }

    /**
     * 将原始内容字符串按 dataId 后缀自动选择解析器，反序列化为指定类型。
     * <ul>
     *   <li>.json  → JSON ObjectMapper</li>
     *   <li>.yaml / .yml → YAML ObjectMapper</li>
     *   <li>其他格式 → 抛 {@link IllegalArgumentException}</li>
     * </ul>
     *
     * @param dataId  目标 DataId（需带后缀）
     * @param content 原始内容字符串
     * @param clazz   目标类型
     * @param <T>     泛型
     * @return 反序列化后的对象
     * @throws IllegalArgumentException 格式不支持
     * @throws Exception                Jackson 反序列化失败
     */
    public <T> T deserialize(String dataId, String content, Class<T> clazz) throws Exception {
        if (dataId.endsWith(".json")) {
            return JSON_MAPPER.readValue(content, clazz);
        } else if (dataId.endsWith(".yaml") || dataId.endsWith(".yml")) {
            return YAML_MAPPER.readValue(content, clazz);
        } else {
            throw new IllegalArgumentException(
                    String.format("[NacosConfig] dataId [%s] 格式不支持整体反序列化，仅支持 .json / .yaml / .yml", dataId));
        }
    }

    /**
     * 将原始内容字符串按 dataId 后缀自动选择解析器，反序列化为 List 类型。
     *
     * @param dataId      目标 DataId（需带后缀）
     * @param content     原始内容字符串
     * @param elementType 集合元素类型
     * @param <T>         泛型
     * @return 反序列化后的列表
     * @throws IllegalArgumentException 格式不支持
     * @throws Exception                Jackson 反序列化失败
     */
    public <T> List<T> deserializeList(String dataId, String content, Class<T> elementType) throws Exception {
        if (dataId.endsWith(".json")) {
            CollectionType listType = JSON_MAPPER.getTypeFactory().constructCollectionType(List.class, elementType);
            return JSON_MAPPER.readValue(content, listType);
        } else if (dataId.endsWith(".yaml") || dataId.endsWith(".yml")) {
            CollectionType listType = YAML_MAPPER.getTypeFactory().constructCollectionType(List.class, elementType);
            return YAML_MAPPER.readValue(content, listType);
        } else {
            throw new IllegalArgumentException(
                    String.format("[NacosConfig] dataId [%s] 格式不支持整体反序列化，仅支持 .json / .yaml / .yml", dataId));
        }
    }

    /**
     * 读取原始字符串值，遍历所有 DataId 分桶查找，未找到返回 null。
     * 若多个 DataId 存在同名 key，按注册顺序返回第一个找到的值。
     */
    public String getRaw(String key) {
        for (Map<String, String> bucket : dataIdCache.values()) {
            String value = bucket.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * 按指定 DataId 精确读取 key 对应的原始字符串值。
     *
     * @param dataId 目标 DataId（需带后缀，如 ai-agent-retry.json）
     * @param key    目标 key
     */
    public String getRaw(String dataId, String key) {
        Map<String, String> bucket = dataIdCache.get(dataId);
        if (bucket == null) {
            log.error("[NacosConfig] dataId 不存在于缓存，dataId={}", dataId);
            return null;
        }
        String value = bucket.get(key);
        if (value == null) {
            log.error("[NacosConfig] dataId 存在但 key 不存在，dataId={}，key={}", dataId, key);
            return null;
        }
        return value;
    }


    // ==================== 类型转换读取（原 NacosConfigUtil 方法，合并为实例方法避免静态字段跨 Bean 竞态）====================

    /**
     * 获取字符串配置
     *
     * @param dataId       目标 DataId 枚举
     * @param key          配置 key
     * @param defaultValue dataId 不存在于缓存时的兜底默认值
     */
    public String getString(NacosDataIdEnum dataId, String key, String defaultValue) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) {
                log.error("[NacosConfig] nacos配置为空 ，dataId={}，key={}", dataId.dataId(), key);
                return defaultValue;
            }
            return value;
        }catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={}，key={}", dataId.dataId(), key, e);
        }
        return defaultValue;
    }

    /**
     * 获取 int 配置
     */
    public int getInt(NacosDataIdEnum dataId, String key, int defaultValue) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return defaultValue;
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={}，key={}", dataId.dataId(), key, e);
        }
        return defaultValue;
    }

    /**
     * 获取 long 配置
     */
    public long getLong(NacosDataIdEnum dataId, String key, long defaultValue) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return defaultValue;
            return Long.parseLong(value.trim());
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={}，key={}", dataId.dataId(), key, e);
        }
        return defaultValue;
    }

    /**
     * 获取 double 配置
     */
    public double getDouble(NacosDataIdEnum dataId, String key, double defaultValue) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return defaultValue;
            return Double.parseDouble(value.trim());
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={}，key={}", dataId.dataId(), key, e);
        }
        return defaultValue;
    }

    /**
     * 获取 boolean 配置（"true"/"false" 不区分大小写）
     */
    public boolean getBoolean(NacosDataIdEnum dataId, String key, boolean defaultValue) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return defaultValue;
            String trimmed = value.trim();
            if ("true".equalsIgnoreCase(trimmed)) return true;
            if ("false".equalsIgnoreCase(trimmed)) return false;
            log.error("[NacosConfig] nacos配置值不是合法的 boolean，dataId={}，key={}，value={}", dataId.dataId(), key, value);
        }catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={}，key={}", dataId.dataId(), key, e);
        }
        return defaultValue;
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
    public <T> T getObject(NacosDataIdEnum dataId, String key, Class<T> clazz) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return null;
            return JsonUtil.readValue(value, clazz);
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={} key={}", dataId.dataId(), key, e);
        }
        return null;
    }

    /**
     * 获取泛型对象配置（适用于嵌套泛型，如 {@code Map<String, List<String>>}）
     */
    public <T> T getObject(NacosDataIdEnum dataId, String key, TypeReference<T> typeRef) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return null;
            return JsonUtil.readValue(value, typeRef);
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={} key={}", dataId.dataId(), key, e);
        }
        return null;
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
    public <T> List<T> getList(NacosDataIdEnum dataId, String key, Class<T> elementType) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return Collections.emptyList();
            return JsonUtil.readList(value, elementType);
        } catch (Exception e) {
            log.error("[NacosConfig] dataId={} key={} 的值无法转换为 List<{}>", dataId.dataId(), key, elementType.getSimpleName(), e);
            return Collections.emptyList();
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
    public <K, V> Map<K, V> getMap(NacosDataIdEnum dataId, String key, Class<K> keyClass, Class<V> valClass) {
        try {
            String value = getRawInternal(dataId, key);
            if (value == null) return Collections.emptyMap();
            return JsonUtil.readValue(value, new TypeReference<Map<K, V>>() {});
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={} key={}", dataId.dataId(), key, e);
        }
        return Collections.emptyMap();
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
    public <T> Set<T> getSet(NacosDataIdEnum dataId, String key, Class<T> elementType) {
        try {
            List<T> list = getList(dataId, key, elementType);
            return list.isEmpty() ? Collections.emptySet() : Set.copyOf(list);
        }catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={} key={}", dataId.dataId(), key, e);
        }
        return Collections.emptySet();
    }

    // ==================== DataId 整体读取 ====================

    /**
     * 将整个 DataId 的原始内容反序列化为指定类型。
     * <p>支持 .json（JSON ObjectMapper）和 .yaml/.yml（YAML ObjectMapper）格式，按后缀自动选择解析器。
     *
     * @param dataId dataId 枚举
     * @param clazz  目标类型
     * @param <T>    泛型
     * @return 解析后的对象；dataId 不存在于缓存时返回 null
     */
    public <T> T getDataIdAsObject(NacosDataIdEnum dataId, Class<T> clazz) {
        try {
            String content = getRawContentInternal(dataId);
            if (content == null) return null;
            return deserialize(dataId.dataId(), content, clazz);
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={}", dataId.dataId(), e);
        }
        return null;
    }

    /**
     * 将整个 DataId 的原始内容反序列化为 List 类型。
     *
     * @param dataId      dataId 枚举
     * @param elementType 集合元素类型
     * @param <T>         泛型
     * @return 解析后的列表；dataId 不存在于缓存时返回空列表
     */
    public <T> List<T> getDataIdAsList(NacosDataIdEnum dataId, Class<T> elementType) {
        try {
            String content = getRawContentInternal(dataId);
            if (content == null) return Collections.emptyList();
            return deserializeList(dataId.dataId(), content, elementType);
        } catch (Exception e) {
            log.error("[NacosConfig] 获取nacos配置异常，dataId={}", dataId.dataId(), e);
        }
        return Collections.emptyList();
    }

    // ==================== 内部校验方法（原 NacosConfigUtil 内部逻辑） ====================

    /**
     * 获取 dataId 对应的原始内容字符串（未解析的原文）。
     * dataId 后缀不合法时抛 {@link IllegalArgumentException}；
     * .properties 格式不支持整体读取，抛 {@link IllegalArgumentException}；
     * dataId 不存在于缓存时返回 null 并打 warn 日志。
     */
    private String getRawContentInternal(NacosDataIdEnum dataId) {
        String dataIdStr = dataId.dataId();
        boolean validSuffix = VALID_SUFFIXES.stream().anyMatch(dataIdStr::endsWith);
        if (!validSuffix) {
            throw new IllegalArgumentException(
                    String.format("[NacosConfig] dataId [%s] 后缀不合法，合法后缀: %s", dataIdStr, VALID_SUFFIXES));
        }
        if (dataIdStr.endsWith(".properties")) {
            throw new IllegalArgumentException(
                    String.format("[NacosConfig] .properties 格式不支持整体读取，dataId=%s，请使用 getString/getInt 等方法按 key 读取", dataIdStr));
        }
        String content = getRawContent(dataIdStr);
        if (content == null) {
            log.warn("[NacosConfig] dataId 不存在于缓存，dataId={}", dataIdStr);
        }
        return content;
    }

    /**
     * 核心读取方法，按 dataId + key 精确查找。
     *
     * @param dataId       目标 DataId 枚举
     * @param key          目标 key
     * @return 找到的原始字符串值；dataId 不存在或 key 不存在时返回 null（并记录 error 日志）
     * @throws IllegalArgumentException dataId 的 dataId() 字符串后缀不合法
     */
    private String getRawInternal(NacosDataIdEnum dataId, String key) {
        String dataIdStr = dataId.dataId();
        boolean validSuffix = VALID_SUFFIXES.stream().anyMatch(dataIdStr::endsWith);
        if (!validSuffix) {
            throw new IllegalArgumentException(
                    String.format("[NacosConfig] dataId [%s] 后缀不合法，合法后缀: %s", dataIdStr, VALID_SUFFIXES));
        }

        Map<String, String> bucket = dataIdCache.get(dataId.dataId());
        if (bucket == null) {
            log.error("[NacosConfig] dataId 不存在于缓存（未配置或未加入索引），dataId={}，key={}", dataIdStr, key);
            return null;
        }

        String value = bucket.get(key);
        if (value == null) {
            log.error("[NacosConfig] dataId 存在但 key 不存在，dataId={}，key={}", dataIdStr, key);
            return null;
        }
        return value;
    }
}

