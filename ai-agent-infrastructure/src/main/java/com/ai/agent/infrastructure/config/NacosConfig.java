package com.ai.agent.infrastructure.config;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final Set<String> registeredDataIds = ConcurrentHashMap.newKeySet();

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
     * 监听器会在 Nacos 推送变更时回调，此时 {@link com.ai.agent.infrastructure.utils.NacosConfigUtil} 缓存已更新完毕，可直接调用读取。
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
     * @return 对应的原始字符串值；DataId 不存在于缓存时返回 null；DataId 存在但 key 不存在时返回 {@link #KEY_NOT_FOUND}
     */
    public String getRaw(String dataId, String key) {
        Map<String, String> bucket = dataIdCache.get(dataId);
        if (bucket == null) {
            return null;
        }
        String value = bucket.get(key);
        return value != null ? value : KEY_NOT_FOUND;
    }

    /** 哨兵值：标识 DataId 存在但 key 不存在，与 DataId 不存在（null）区分 */
    public static final String KEY_NOT_FOUND = "__KEY_NOT_FOUND__";
}

