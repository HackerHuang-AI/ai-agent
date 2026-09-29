package com.ai.agent.starter.handler;

import com.ai.agent.application.model.request.RequestContext;
import com.ai.agent.application.model.request.RequestContextHolder;
import com.ai.agent.application.common.TraceContextSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class TraceIdInterceptorHandler implements HandlerInterceptor {
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String USER_ID_HEADER = "X-Internal-User-Id";
    private static final String TENANT_ID_HEADER = "X-Internal-Tenant-Id";
    private static final String SESSION_ID_HEADER = "X-Internal-Session-Id";
    private static final String CALL_CHAIN_HEADER = "X-Call-Chain";
    private static final String MDC_TRACE_ID = "traceId";

    private final String applicationName;

    public TraceIdInterceptorHandler(@Value("${spring.application.name:ai-agent}") String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        TraceContextSupport.TraceContext traceContext = TraceContextSupport.from(request.getHeader(TRACEPARENT_HEADER));
        String traceparent = TraceContextSupport.childTraceparent(traceContext);
        response.setHeader(TRACEPARENT_HEADER, traceparent);
        String callChain = appendApplication(request.getHeader(CALL_CHAIN_HEADER));
        if (TraceContextSupport.violatesCallChainPolicy(callChain)) {
            String previousTraceId = MDC.get(MDC_TRACE_ID);
            MDC.put(MDC_TRACE_ID, traceContext.traceId());
            try {
                log.warn("[CALL_CHAIN] request rejected | reason=policy_violation");
            } finally {
                if (previousTraceId == null) {
                    MDC.remove(MDC_TRACE_ID);
                } else {
                    MDC.put(MDC_TRACE_ID, previousTraceId);
                }
            }
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            return false;
        }
        RequestContextHolder.set(new RequestContext(traceContext.traceId(), traceparent,
                request.getHeader(USER_ID_HEADER), request.getHeader(TENANT_ID_HEADER),
                request.getHeader(SESSION_ID_HEADER), callChain));
        MDC.put(MDC_TRACE_ID, traceContext.traceId());
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        RequestContextHolder.clear();
        MDC.remove(MDC_TRACE_ID);
    }

    private String appendApplication(String callChain) {
        return callChain == null || callChain.isBlank() ? applicationName : callChain + ";" + applicationName;
    }
}

