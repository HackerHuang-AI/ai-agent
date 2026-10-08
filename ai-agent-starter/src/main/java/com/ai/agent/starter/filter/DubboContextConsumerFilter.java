package com.ai.agent.starter.filter;

import com.ai.agent.application.common.TraceContextSupport;
import com.ai.agent.application.model.request.RequestContext;
import com.ai.agent.application.model.request.RequestContextHolder;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Filter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;

/**
 * Dubbo 消费端上下文过滤器，将当前请求的链路与身份信息透传至下游服务。
 *
 * @author HUANGcong
 * @since 2026-05-29
 */
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

