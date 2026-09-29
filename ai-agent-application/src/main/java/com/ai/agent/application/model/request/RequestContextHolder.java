package com.ai.agent.application.model.request;

import java.util.Optional;

public final class RequestContextHolder {
    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static Optional<RequestContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static void set(RequestContext context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }
}

