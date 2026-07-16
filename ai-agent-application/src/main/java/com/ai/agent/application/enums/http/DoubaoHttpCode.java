package com.ai.agent.application.enums.http;

import lombok.Getter;

/**
 * 豆包（火山方舟）平台 HTTP 响应状态码枚举。
 *
 * <p>参考：<a href="https://www.volcengine.com/docs/82379/1099475">火山方舟错误码文档</a>
 * <p>注：火山方舟无独立 402 余额不足码，余额问题通过 400 + error.code 字段区分。
 */
@Getter
public enum DoubaoHttpCode {

    UNAUTHORIZED (401, "API Key 无效或已过期"),
    BAD_REQUEST  (400, "请求参数错误"),
    UNPROCESSABLE(422, "请求体格式无法处理"),
    RATE_LIMIT   (429, "请求速率超限");

    private final int code;
    private final String description;

    DoubaoHttpCode(int code, String description) {
        this.code = code;
        this.description = description;
    }
}

