package com.ai.agent.infrastructure.enums;

/**
 * @Description: LLM 平台重试策略枚举，定义各平台对应的 Nacos 重试配置 key。
 *
 * <p>使用方式：
 * <ol>
 *   <li>根据 platform 字符串调用 {@link #of(String)} 查找对应枚举项</li>
 *   <li>找不到时自动返回 {@link #DEFAULT}，使用兜底 Nacos key</li>
 *   <li>拿到枚举项的 {@code nacosKey} 去 {@code ai-agent-retry.json} 读配置</li>
 *   <li>Nacos 未配置时由 RetryConfig 使用代码内置默认值兜底</li>
 * </ol>
 *
 * <p>新增平台时只需在此追加一行枚举项即可，RetryConfig 无需改动。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.enums
 * @ClassName: PlatformRetryDef
 * @Author: HUANGcong
 * @Date: Created in 2026/7/9
 * @Version: 1.0
 */
public enum PlatformRetryDef {

    DOUBAO       ("doubao"),
    DEEPSEEK     ("deepseek"),
    QWEN         ("qwen"),
    ZHIPU        ("zhipu"),
    MOONSHOT     ("moonshot"),
    OPENAI       ("openai"),
    ANTHROPIC    ("anthropic"),
    GEMINI       ("gemini"),
    MIMO         ("mimo"),
    MINIMAX      ("minimax"),
    OLLAMA       ("ollama"),
    QIANFAN      ("qianfan"),
    TOKENHUB     ("tokenhub"),
    HY_TOKENPLAN ("hy_tokenplan"),
    DS_TOKENPLAN ("ds_tokenplan"),

    /** 兜底项：找不到专属枚举时使用，对应 Nacos 中的全局 LLM 重试配置 */
    DEFAULT      ("llm"),
    ;

    /** 对应 ai-agent-retry.json 中的 key */
    public final String nacosKey;

    PlatformRetryDef(String nacosKey) {
        this.nacosKey = nacosKey;
    }

    /**
     * 根据 platform 字符串查找对应枚举项。
     * 找不到时返回 {@link #DEFAULT}，不抛异常。
     *
     * @param platform 平台标识（不区分大小写）
     */
    public static PlatformRetryDef of(String platform) {
        if (platform == null || platform.isBlank()) {
            return DEFAULT;
        }
        String key = platform.toLowerCase();
        for (PlatformRetryDef def : values()) {
            if (def != DEFAULT && def.nacosKey.equals(key)) {
                return def;
            }
        }
        return DEFAULT;
    }
}

