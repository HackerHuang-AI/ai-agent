package com.ai.agent.application.enums.http;

import lombok.Getter;

/**
 * 百度千帆平台 HTTP 响应状态码枚举。
 *
 * <p>参考：<a href="https://cloud.baidu.com/doc/WENXINWORKSHOP/s/tlmyncueh">千帆错误码文档</a>
 */
@Getter
public enum QianfanHttpCodeEnum {

    UNAUTHORIZED  (401, "API Key 无效或已过期"),
    BAD_REQUEST   (400, "请求参数错误"),
    UNPROCESSABLE (422, "请求体格式无法处理"),
    RATE_LIMIT    (429, "请求速率超限");

    private final int code;
    private final String description;

    QianfanHttpCodeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }
}

