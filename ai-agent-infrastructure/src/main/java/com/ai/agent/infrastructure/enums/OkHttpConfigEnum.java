package com.ai.agent.infrastructure.enums;

/**
 * @Description: OkHttp 超时策略作用域枚举，定义各业务场景对应的 Nacos 超时配置 key。
 *
 * <p>不局限于 LLM 平台，任何需要独立配置超时参数的业务场景均可在此注册一行。
 *
 * <p>使用方式：
 * <ol>
 *   <li>调用 {@link #of(String)} 根据 scope 字符串查找枚举项，找不到返回 {@link #DEFAULT}</li>
 *   <li>用枚举项的 {@code nacosKey} 去 {@code ai-agent-http.json} 读配置</li>
 *   <li>Nacos 未配置时由 OkHttpConfig 使用全局 okhttp 参数兜底</li>
 * </ol>
 *
 * <p>新增业务场景只需在此追加一行枚举项，OkHttpConfig 无需改动。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.enums
 * @ClassName: OkHttpConfigEnum
 * @Author: HUANGcong
 * @Date: Created in 2026/7/9
 * @Version: 1.0
 */
public enum OkHttpConfigEnum {

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

    /** 兜底项：找不到专属枚举时使用，对应 Nacos ai-agent-http.json 中的 {@code "default"} 全局超时配置块 */
    DEFAULT      ("default"),
    ;

    /** 对应 ai-agent-http.json 中的 key */
    public final String nacosKey;

    OkHttpConfigEnum(String nacosKey) {
        this.nacosKey = nacosKey;
    }

    /**
     * 根据 scope 字符串查找对应枚举项。
     * 找不到时返回 {@link #DEFAULT}，不抛异常。
     *
     * @param scope 业务场景标识（不区分大小写）
     */
    public static OkHttpConfigEnum of(String scope) {
        if (scope == null || scope.isBlank()) {
            return DEFAULT;
        }
        String key = scope.toLowerCase();
        for (OkHttpConfigEnum item : values()) {
            if (item != DEFAULT && item.nacosKey.equals(key)) {
                return item;
            }
        }
        return DEFAULT;
    }
}

