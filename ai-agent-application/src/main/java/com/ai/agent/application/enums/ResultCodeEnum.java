package com.ai.agent.application.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * @Description: 统一响应状态码枚举，配合 Result<T> 使用。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.enums
 * @ClassName: ResultCodeEnum
 * @Author: HUANGcong
 * @Date: Created in 2026/5/29
 * @Version: 1.0
 */
public enum ResultCodeEnum {

    SUCCESS("00", "成功"),
    ERROR("01", "失败");

    private final String code;
    private final String defaultMessage;

    ResultCodeEnum(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** Jackson 序列化时输出此值（"00"/"01"），而非枚举名 */
    @JsonValue
    public String getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    @JsonCreator
    public static ResultCodeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ResultCodeEnum rc : values()) {
            if (rc.code.equals(code)) {
                return rc;
            }
        }
        throw new IllegalArgumentException("Invalid result code: " + code);
    }
}

