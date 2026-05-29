package com.ai.agent.starter.handler;

import com.ai.agent.application.common.BizException;
import com.ai.agent.starter.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;
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
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    /**
     * 处理业务异常（BizException），按请求语言解析 i18n 描述，携带 errorCode 返回。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException ex, HttpServletRequest request) {
        Locale locale = localeResolver.resolveLocale(request);
        String message;
        try {
            message = messageSource.getMessage(ex.getErrorCode().getMessageKey(), ex.getArgs(), locale);
        } catch (Exception e) {
            message = ex.getErrorCode().getDefaultMessage();
        }
        log.warn("[业务异常] errorCode={}, message={}", ex.getErrorCode().getCode(), message);
        return Result.error(ex.getErrorCode(), message);
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
        log.warn("[参数校验失败] {}", message);
        return Result.error(message);
    }

    /**
     * 处理业务层主动抛出的 IllegalArgumentException。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("[非法参数] {}", ex.getMessage());
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

