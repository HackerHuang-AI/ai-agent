package com.ai.agent.infrastructure.config;

/**
 * @Description: Nacos 配置文件 DataId 枚举，统一管理本服务在 Nacos 上注册的所有配置文件。
 *
 * <p>使用规范：
 * <ul>
 *   <li>所有调用 {@code NacosConfigUtil} 的地方必须使用此枚举，禁止直接传字符串 DataId</li>
 *   <li>新增配置文件时，先在此处添加枚举项，再去 Nacos 控制台创建对应 DataId 并加入索引文件</li>
 *   <li>枚举命名规则：DataId 去掉后缀，其余部分 {@code -} 换 {@code _} 并全大写，
 *       如 ai-agent-retry.json → AI_AGENT_RETRY</li>
 * </ul>
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.config
 * @ClassName: NacosDataId
 * @Author: HUANGcong
 * @Date: Created in 2026/6/26
 * @Version: 1.0
 */
public enum NacosDataId {

    // 待业务 DataId 确定后在此追加，示例：
    // AI_AGENT_LLM("ai-agent-llm.json"),
    // AI_AGENT_RETRY("ai-agent-retry.json"),

    ;

    // ==================== 构造 ====================

    private final String dataId;

    NacosDataId(String dataId) {
        this.dataId = dataId;
    }

    /**
     * 返回 Nacos 上的完整 DataId 字符串，如 {@code "ai-agent-retry.json"}。
     */
    public String dataId() {
        return dataId;
    }
}

