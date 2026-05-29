package com.ai.agent.starter.common;

import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.enums.ResultCodeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    /** 业务错误码，成功时为 null */
    private String errorCode;

    /** 响应数据 */
    private T data;

    // ==================== 成功 ====================

    public static <T> Result<T> success() {
        return new Result<>(ResultCodeEnum.SUCCESS, ResultCodeEnum.SUCCESS.getDefaultMessage(), null, null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCodeEnum.SUCCESS, ResultCodeEnum.SUCCESS.getDefaultMessage(), null, data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCodeEnum.SUCCESS, message, null, data);
    }

    // ==================== 失败 ====================

    public static <T> Result<T> error() {
        return new Result<>(ResultCodeEnum.ERROR, ResultCodeEnum.ERROR.getDefaultMessage(), null, null);
    }

    public static <T> Result<T> error(String message) {
        return new Result<>(ResultCodeEnum.ERROR, message, null, null);
    }

    public static <T> Result<T> error(ResultCodeEnum code, String message) {
        return new Result<>(code, message, null, null);
    }

    public static <T> Result<T> error(ErrorCodeEnum errorCode, String message) {
        return new Result<>(ResultCodeEnum.ERROR, message, errorCode.getCode(), null);
    }

    // ==================== 判断 ====================

    public boolean isSuccess() {
        return ResultCodeEnum.SUCCESS.equals(this.code);
    }
}

