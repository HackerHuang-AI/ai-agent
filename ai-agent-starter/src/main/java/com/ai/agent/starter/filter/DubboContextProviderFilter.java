package com.ai.agent.starter.filter;

import com.ai.agent.application.common.TraceContextSupport;
import com.ai.agent.application.model.request.RequestContext;
import com.ai.agent.application.model.request.RequestContextHolder;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.slf4j.MDC;

/**
 * Dubbo 服务端上下文过滤器，恢复上游链路与身份信息并校验调用链。
 *
 * @author HUANGcong
 * @since 2026-05-29
 */
@Activate(group = "provider")
public class DubboContextProviderFilter implements Filter {
    private static final String TRACE_ID = "traceId";
    private static final String CALL_CHAIN = "callChain";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        RequestContext previousContext = RequestContextHolder.current().orElse(null);
        String previousTraceId = MDC.get(TRACE_ID);
        String previousCallChain = MDC.get(CALL_CHAIN);
        String callChain = appendApplication(invocation.getAttachment(DubboContextConsumerFilter.CALL_CHAIN));
        if (TraceContextSupport.violatesCallChainPolicy(callChain)) {
            throw new RpcException("call chain violates the configured policy");
        }
        TraceContextSupport.TraceContext traceContext = TraceContextSupport.from(
                invocation.getAttachment(DubboContextConsumerFilter.TRACEPARENT));
        String traceparent = TraceContextSupport.childTraceparent(traceContext);
        RequestContextHolder.set(new RequestContext(traceContext.traceId(), traceparent,
                invocation.getAttachment(DubboContextConsumerFilter.USER_ID),
                invocation.getAttachment(DubboContextConsumerFilter.TENANT_ID),
                invocation.getAttachment(DubboContextConsumerFilter.SESSION_ID), callChain));
        MDC.put(TRACE_ID, traceContext.traceId());
        MDC.put(CALL_CHAIN, TraceContextSupport.displayCallChain(callChain));
        try {
            return invoker.invoke(invocation);
        } finally {
            restore(previousContext, previousTraceId, previousCallChain);
        }
    }

    private String appendApplication(String callChain) {
        String applicationName = ApplicationModel.defaultModel().tryGetApplicationName();
        return callChain == null || callChain.isBlank() ? applicationName : callChain + ";" + applicationName;
    }

    private void restore(RequestContext previousContext, String previousTraceId, String previousCallChain) {
        if (previousContext == null) {
            RequestContextHolder.clear();
        } else {
            RequestContextHolder.set(previousContext);
        }
        restoreMdc(TRACE_ID, previousTraceId);
        restoreMdc(CALL_CHAIN, previousCallChain);
    }

    private void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}

