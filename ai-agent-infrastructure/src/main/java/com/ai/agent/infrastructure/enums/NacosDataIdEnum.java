package com.ai.agent.infrastructure.enums;

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
 * @Package: com.ai.agent.infrastructure.enums
 * @ClassName: NacosDataIdEnum
 * @Author: HUANGcong
 * @Date: Created in 2026/6/26
 * @Version: 1.0
 */
public enum NacosDataIdEnum {

    /** LLM 流式线程池参数配置，对应 Nacos 上的 ai-agent-thread-pool.json */
    AI_AGENT_THREAD_POOL("ai-agent-thread-pool.json"),

    /** 豆包平台默认凭证配置，对应 Nacos 上的 ai-agent-doubao.json */
    AI_AGENT_DOUBAO("ai-agent-doubao.json"),

    /** Deepseek 平台默认凭证配置，对应 Nacos 上的 ai-agent-deepseek.json */
    AI_AGENT_DEEPSEEK("ai-agent-deepseek.json"),

    /** Qwen（阿里云百炼）平台默认凭证配置，对应 Nacos 上的 ai-agent-qwen.json */
    AI_AGENT_QWEN("ai-agent-qwen.json"),

    /** 智谱 GLM 平台默认凭证配置，对应 Nacos 上的 ai-agent-zhipu.json */
    AI_AGENT_ZHIPU("ai-agent-zhipu.json"),

    /** Moonshot（Kimi）平台默认凭证配置，对应 Nacos 上的 ai-agent-moonshot.json */
    AI_AGENT_MOONSHOT("ai-agent-moonshot.json"),

    /** OpenAI 平台默认凭证配置，对应 Nacos 上的 ai-agent-openai.json */
    AI_AGENT_OPENAI("ai-agent-openai.json"),

    /** Anthropic（Claude）平台默认凭证配置，对应 Nacos 上的 ai-agent-anthropic.json */
    AI_AGENT_ANTHROPIC("ai-agent-anthropic.json"),

    /** Google Gemini 平台默认凭证配置，对应 Nacos 上的 ai-agent-gemini.json */
    AI_AGENT_GEMINI("ai-agent-gemini.json"),

    /** 小米 MiMo 平台默认凭证配置，对应 Nacos 上的 ai-agent-mimo.json */
    AI_AGENT_MIMO("ai-agent-mimo.json"),

    /** Minimax 平台默认凭证配置，对应 Nacos 上的 ai-agent-minimax.json */
    AI_AGENT_MINIMAX("ai-agent-minimax.json"),

    /** Ollama 本地部署默认配置，对应 Nacos 上的 ai-agent-ollama.json */
    AI_AGENT_OLLAMA("ai-agent-ollama.json"),

    /** 百度千帆平台默认凭证配置，对应 Nacos 上的 ai-agent-qianfan.json */
    AI_AGENT_QIANFAN("ai-agent-qianfan.json"),

    /** 腾讯 TokenHub 平台默认凭证配置，对应 Nacos 上的 ai-agent-tokenhub.json */
    AI_AGENT_TOKENHUB("ai-agent-tokenhub.json"),

    /** 阿里云灵积 Token Plan 默认凭证配置，对应 Nacos 上的 ai-agent-dashscope-tokenplan.json */
    AI_AGENT_DASHSCOPE_TOKENPLAN("ai-agent-dashscope-tokenplan.json"),

    /** OkHttp 客户端连接参数动态配置，对应 Nacos 上的 ai-agent-http.json */
    AI_AGENT_HTTP("ai-agent-http.json"),

    /** 重试策略配置（通用/LLM全局/各平台专属），对应 Nacos 上的 ai-agent-retry.json */
    AI_AGENT_RETRY("ai-agent-retry.json"),

    ;

    // ==================== 构造 ====================

    private final String dataId;

    NacosDataIdEnum(String dataId) {
        this.dataId = dataId;
    }

    /**
     * 返回 Nacos 上的完整 DataId 字符串，如 {@code "ai-agent-http.json"}。
     */
    public String dataId() {
        return dataId;
    }
}

