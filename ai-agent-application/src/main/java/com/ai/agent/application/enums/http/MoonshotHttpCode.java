package com.ai.agent.application.enums.http;

import lombok.Getter;

/**
 * Moonshot（Kimi）平台 HTTP 响应状态码枚举。
 *
 * <p>参考：<a href="https://platform.moonshot.cn/docs/api/error">Moonshot 错误码文档</a>
 */
@Getter
public enum MoonshotHttpCode {

    UNAUTHORIZED      (401, "API Key 无效或已过期"),
    INSUFFICIENT_FUNDS(402, "账号余额不足"),
    BAD_REQUEST       (400, "请求参数错误"),
    UNPROCESSABLE     (422, "请求体格式无法处理"),
    RATE_LIMIT        (429, "请求速率超限");

    private final int code;
    private final String description;

    MoonshotHttpCode(int code, String description) {
        this.code = code;
        this.description = description;
    }
}

