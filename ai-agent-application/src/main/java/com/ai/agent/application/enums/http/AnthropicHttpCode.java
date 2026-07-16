package com.ai.agent.application.enums.http;

import lombok.Getter;

/**
 * Anthropic（Claude）平台 HTTP 响应状态码枚举。
 *
 * <p>参考：<a href="https://docs.anthropic.com/en/api/errors">Anthropic 错误码文档</a>
 * <p>注：529 为 Anthropic 自定义的服务过载码，语义等同于限速，触发重试策略。
 */
@Getter
public enum AnthropicHttpCode {

    UNAUTHORIZED  (401, "API Key 无效或已过期"),
    BAD_REQUEST   (400, "请求参数错误"),
    UNPROCESSABLE (422, "请求体格式无法处理"),
    RATE_LIMIT    (429, "请求速率超限"),
    OVERLOADED    (529, "服务过载，请稍后重试");

    private final int code;
    private final String description;

    AnthropicHttpCode(int code, String description) {
        this.code = code;
        this.description = description;
    }
}

