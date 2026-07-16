package com.ai.agent.application.enums.http;

import lombok.Getter;

/**
 * Ollama 平台 HTTP 响应状态码枚举。
 *
 * <p>参考：<a href="https://github.com/ollama/ollama/blob/main/docs/api.md">Ollama API 文档</a>
 * <p>注：Ollama 通常为本地部署；403 表示未设置认证或权限不足，与 401 同属认证类错误。
 */
@Getter
public enum OllamaHttpCodeEnum {

    UNAUTHORIZED  (401, "未设置认证或 API Key 无效"),
    FORBIDDEN     (403, "无访问权限"),
    BAD_REQUEST   (400, "请求参数错误"),
    UNPROCESSABLE (422, "请求体格式无法处理"),
    RATE_LIMIT    (429, "请求速率超限");

    private final int code;
    private final String description;

    OllamaHttpCodeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }
}

