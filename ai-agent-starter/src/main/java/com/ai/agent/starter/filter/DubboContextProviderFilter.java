package com.ai.agent.starter.filter;

import com.ai.agent.application.model.request.RequestContext;
import com.ai.agent.application.model.request.RequestContextHolder;
import com.ai.agent.application.common.TraceContextSupport;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.*;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.slf4j.MDC;

@Activate(group = "provider")
public class DubboContextProviderFilter implements Filter {
    private static final String TRACE_ID = "traceId";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
        RequestContext previousContext = RequestContextHolder.current().orElse(null);
        String previousTraceId = MDC.get(TRACE_ID);
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
        try {
            return invoker.invoke(invocation);
        } finally {
            restore(previousContext, previousTraceId);
        }
    }

    private String appendApplication(String callChain) {
        String applicationName = ApplicationModel.defaultModel().tryGetApplicationName();
        return callChain == null || callChain.isBlank() ? applicationName : callChain + ";" + applicationName;
    }

    private void restore(RequestContext previousContext, String previousTraceId) {
        if (previousContext == null) {
            RequestContextHolder.clear();
        } else {
            RequestContextHolder.set(previousContext);
        }
        if (previousTraceId == null) {
            MDC.remove(TRACE_ID);
        } else {
            MDC.put(TRACE_ID, previousTraceId);
        }
    }
}

