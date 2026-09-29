package com.ai.agent.starter.common;

import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.enums.ResultCodeEnum;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * @Description: 统一响应体，所有 Controller 接口返回值均用此类包装。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.common
 * @ClassName: Result
 * @Author: HUANGcong
 * @Date: Created in 2026/5/29
 * @Version: 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /** 状态码，序列化后输出枚举的 code 值，如 "00" / "01" */
    private ResultCodeEnum code;

    /** 提示信息 */
    private String message;

    /** 业务错误码，成功时不输出 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String errorCode;

    /** 响应数据 */
    private T data;

    /** 结构化错误明细，成功时不输出 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ErrorItem> errors;

    // ==================== 成功 ====================

    public static <T> Result<T> success() {
        return new Result<>(ResultCodeEnum.SUCCESS, ResultCodeEnum.SUCCESS.getDefaultMessage(), null, null, null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCodeEnum.SUCCESS, ResultCodeEnum.SUCCESS.getDefaultMessage(), null, data, null);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCodeEnum.SUCCESS, message, null, data, null);
    }

    // ==================== 失败 ====================

    public static <T> Result<T> error(ErrorCodeEnum errorCode, String message) {
        return error(errorCode, message, List.of());
    }

    public static <T> Result<T> error(ErrorCodeEnum errorCode, String message, List<ErrorItem> errors) {
        return new Result<>(ResultCodeEnum.ERROR, message, errorCode.getCode(), null, List.copyOf(errors));
    }
}

