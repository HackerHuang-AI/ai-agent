package com.ai.agent.starter.filter;

import com.ai.agent.application.model.request.RequestContext;
import com.ai.agent.application.model.request.RequestContextHolder;
import com.ai.agent.application.common.TraceContextSupport;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;

@Activate(group = "consumer")
public class DubboContextConsumerFilter implements Filter {
    static final String TRACEPARENT = "traceparent";
    static final String USER_ID = "X-Internal-User-Id";
    static final String TENANT_ID = "X-Internal-Tenant-Id";
    static final String SESSION_ID = "X-Internal-Session-Id";
    static final String CALL_CHAIN = "X-Call-Chain";

    @Override
    public Result invoke(Invoker<?> invoker, Invocation invocation) {
        RequestContextHolder.current().ifPresent(context -> propagate(invocation, context));
        return invoker.invoke(invocation);
    }

    private void propagate(Invocation invocation, RequestContext context) {
        invocation.setAttachment(TRACEPARENT, TraceContextSupport.childTraceparent(context.traceparent()));
        setAttachment(invocation, USER_ID, context.userId());
        setAttachment(invocation, TENANT_ID, context.tenantId());
        setAttachment(invocation, SESSION_ID, context.sessionId());
        setAttachment(invocation, CALL_CHAIN, context.callChain());
    }

    private void setAttachment(Invocation invocation, String key, String value) {
        if (value != null && !value.isBlank()) {
            invocation.setAttachment(key, value);
        }
    }
}

