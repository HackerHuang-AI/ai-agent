package com.ai.agent.application.enums.http;

import lombok.Getter;

/**
 * Gemini（Google AI）平台 HTTP 响应状态码枚举。
 *
 * <p>参考：<a href="https://ai.google.dev/gemini-api/docs/troubleshooting">Gemini 错误码文档</a>
 * <p>注：通过 OpenAI 兼容层接入；403 表示权限不足（如地区限制），与 401 同属认证类错误。
 */
@Getter
public enum GeminiHttpCodeEnum {

    UNAUTHORIZED  (401, "API Key 无效或已过期"),
    FORBIDDEN     (403, "无访问权限（如地区限制）"),
    BAD_REQUEST   (400, "请求参数错误"),
    UNPROCESSABLE (422, "请求体格式无法处理"),
    RATE_LIMIT    (429, "请求速率超限");

    private final int code;
    private final String description;

    GeminiHttpCodeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }
}

