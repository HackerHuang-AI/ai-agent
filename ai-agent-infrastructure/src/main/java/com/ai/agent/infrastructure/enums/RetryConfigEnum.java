package com.ai.agent.infrastructure.enums;

/**
 * @Description: 重试策略作用域枚举，定义各业务场景对应的 Nacos 重试配置 key。
 *
 * <p>不局限于 LLM 平台，任何需要独立配置重试策略的业务场景均可在此注册一行。
 *
 * <p>使用方式：
 * <ol>
 *   <li>调用 {@link #of(String)} 根据 scope 字符串查找枚举项，找不到返回 {@link #DEFAULT}</li>
 *   <li>用枚举项的 {@code nacosKey} 去 {@code ai-agent-retry.json} 读配置</li>
 *   <li>Nacos 未配置时由 RetryConfig 使用代码内置默认值兜底</li>
 * </ol>
 *
 * <p>新增业务场景只需在此追加一行枚举项，RetryConfig 无需改动。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.enums
 * @ClassName: RetryConfigEnum
 * @Author: HUANGcong
 * @Date: Created in 2026/7/9
 * @Version: 1.0
 */
public enum RetryConfigEnum {

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
    DS_TOKENPLAN ("ds_tokenplan"),

    /** 兜底项：找不到专属枚举时使用，对应 Nacos 中的全局通用重试配置 */
    DEFAULT      ("default"),
    ;

    /** 对应 ai-agent-retry.json 中的 key */
    public final String nacosKey;

    RetryConfigEnum(String nacosKey) {
        this.nacosKey = nacosKey;
    }

    /**
     * 根据 scope 字符串查找对应枚举项。
     * 找不到时返回 {@link #DEFAULT}，不抛异常。
     *
     * @param scope 业务场景标识（不区分大小写）
     */
    public static RetryConfigEnum of(String scope) {
        if (scope == null || scope.isBlank()) {
            return DEFAULT;
        }
        String key = scope.toLowerCase();
        for (RetryConfigEnum item : values()) {
            if (item != DEFAULT && item.nacosKey.equals(key)) {
                return item;
            }
        }
        return DEFAULT;
    }
}

