package com.ai.agent.application.enums.http;

import lombok.Getter;

/**
 * MiniMax 平台 HTTP 响应状态码枚举。
 *
 * <p>参考：<a href="https://platform.minimaxi.com/document/error">MiniMax 错误码文档</a>
 * <p>注：MiniMax 业务层错误通过 base_resp.status_code 字段额外传递，此处仅覆盖 HTTP 层状态码。
 */
@Getter
public enum MinimaxHttpCode {

    UNAUTHORIZED  (401, "API Key 无效或已过期"),
    BAD_REQUEST   (400, "请求参数错误"),
    UNPROCESSABLE (422, "请求体格式无法处理"),
    RATE_LIMIT    (429, "请求速率超限");

    private final int code;
    private final String description;

    MinimaxHttpCode(int code, String description) {
        this.code = code;
        this.description = description;
    }
}

