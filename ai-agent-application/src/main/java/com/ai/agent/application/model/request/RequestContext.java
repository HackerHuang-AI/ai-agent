package com.ai.agent.application.model.request;

public record RequestContext(
        String traceId,
        String traceparent,
        String userId,
        String tenantId,
        String sessionId,
        String callChain
) {
}

