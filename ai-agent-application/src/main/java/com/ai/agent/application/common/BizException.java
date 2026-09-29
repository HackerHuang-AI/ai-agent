package com.ai.agent.application.common;

import com.ai.agent.application.enums.ErrorCodeEnum;
import lombok.Getter;

import java.util.List;

/**
 * @Description: 业务异常，携带结构化错误码，用于 Service 层主动抛出可预期的业务错误。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.common
 * @ClassName: BizException
 * @Author: HUANGcong
 * @Date: Created in 2026/5/29
 * @Version: 1.0
 */
@Getter
public class BizException extends RuntimeException {

    /** 业务错误码 */
    private final ErrorCodeEnum errorCode;

    /** 结构化错误明细 */
    private final List<ErrorCodeEnum> errorDetails;

    /** i18n 占位符参数，对应 messages.properties 中的 {0}、{1} 等 */
    private final Object[] args;

    public BizException(ErrorCodeEnum errorCode, Object... args) {
        this(errorCode, List.of(), args);
    }

    public BizException(ErrorCodeEnum errorCode, ErrorCodeEnum errorDetail) {
        this(errorCode, List.of(errorDetail));
    }

    public BizException(ErrorCodeEnum errorCode, List<ErrorCodeEnum> errorDetails, Object... args) {
        super(errorCode.getDefaultMessage());
        this.errorCode = errorCode;
        this.errorDetails = List.copyOf(errorDetails);
        this.args = args;
    }

    public BizException(ErrorCodeEnum errorCode, String platformDetail) {
        this(errorCode, List.of());
    }
}

