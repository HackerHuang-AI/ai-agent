package com.ai.agent.starter.handler;

import com.ai.agent.application.common.BizException;
import com.ai.agent.starter.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * @Description: 全局异常处理器，统一处理 Controller 层的参数校验失败和运行时异常。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.handler
 * @ClassName: GlobalExceptionHandler
 * @Author: HUANGcong
 * @Date: Created in 2026/5/29
 * @Version: 1.0
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常（BizException），直接使用异常 message 返回，平台错误信息已在构造时拼入。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException ex) {
        log.error("[业务异常] errorCode={}, message={}", ex.getErrorCode().getCode(), ex.getMessage());
        return Result.error(ex.getErrorCode(), ex.getMessage());
    }

    /**
     * 处理 @Valid + @RequestBody 的 Bean Validation 失败。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.error("[参数校验失败] {}", message);
        return Result.error(message);
    }

    /**
     * 处理业务层主动抛出的 IllegalArgumentException。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.error("[非法参数] {}", ex.getMessage());
        return Result.error(ex.getMessage());
    }

    /**
     * 兜底处理所有未捕获的运行时异常，防止堆栈信息泄露给调用方。
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex) {
        log.error("[系统异常]", ex);
        return Result.error("服务器内部错误，请稍后重试");
    }
}

