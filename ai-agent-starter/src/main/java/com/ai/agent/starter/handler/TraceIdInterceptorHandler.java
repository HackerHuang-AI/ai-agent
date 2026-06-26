package com.ai.agent.starter.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

/**
 * @Description: TraceId 拦截器
 *               请求进来时：读取 Header X-Trace-Id，有则复用，无则自动生成，注入 MDC。
 *               同时读取 X-Source-AppKey，标记调用来源系统，方便按 appKey 过滤日志。
 *               请求结束时：清除 MDC，防止线程池复用时污染。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.handler
 * @ClassName: TraceIdInterceptor
 * @Author: HUANGcong
 * @Date: Created in 2026/6/24
 * @Version: 1.0
 */
@Component
public class TraceIdInterceptorHandler implements HandlerInterceptor {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String APP_KEY_HEADER = "X-Source-AppKey";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_APP_KEY = "sourceAppKey";

    @Value("${spring.application.name:ai-agent}")
    private String applicationName;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        String sourceAppKey = request.getHeader(APP_KEY_HEADER);
        MDC.put(MDC_APP_KEY, (sourceAppKey != null && !sourceAppKey.isBlank()) ? sourceAppKey : applicationName);
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        MDC.clear();
    }
}

