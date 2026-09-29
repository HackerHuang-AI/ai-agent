package com.ai.agent.application.common;

import com.ai.agent.application.model.request.RequestContext;
import com.ai.agent.application.model.request.RequestContextHolder;
import org.slf4j.MDC;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public final class TraceContextSupport {
    private static final String MDC_TRACE_ID = "traceId";
    private static final String CALL_CHAIN_LIMIT_PREFIX = "limit=";
    private static final String CALL_CHAIN_LENGTH_PREFIX = ",length=";
    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");

    private TraceContextSupport() {
    }

    public static TraceContext from(String traceparent) {
        if (traceparent == null || traceparent.isBlank()) {
            return new TraceContext(newTraceId(), "01");
        }
        var matcher = TRACEPARENT_PATTERN.matcher(traceparent.toLowerCase(Locale.ROOT));
        if (!matcher.matches() || isAllZeros(matcher.group(1)) || isAllZeros(matcher.group(2))) {
            return new TraceContext(newTraceId(), "01");
        }
        return new TraceContext(matcher.group(1), matcher.group(3));
    }

    public static String childTraceparent(String traceparent) {
        return childTraceparent(from(traceparent));
    }

    public static String childTraceparent(TraceContext context) {
        return "00-" + context.traceId() + "-" + newSpanId() + "-" + context.traceFlags();
    }

    public static boolean violatesCallChainPolicy(String callChain) {
        if (callChain == null || callChain.isBlank()) {
            return false;
        }
        String[] segments = callChain.split(";", -1);
        String policy = segments[0];
        if (!policy.startsWith(CALL_CHAIN_LIMIT_PREFIX)) {
            return false;
        }
        int separatorIndex = policy.indexOf(CALL_CHAIN_LENGTH_PREFIX);
        if (separatorIndex < 0 || hasBlankSegment(segments)) {
            return true;
        }
        try {
            int maxHops = Integer.parseInt(policy.substring(CALL_CHAIN_LIMIT_PREFIX.length(), separatorIndex));
            int maxLength = Integer.parseInt(policy.substring(separatorIndex + CALL_CHAIN_LENGTH_PREFIX.length()));
            return maxHops < 1 || maxLength < 1 || segments.length - 1 > maxHops || callChain.length() > maxLength;
        } catch (NumberFormatException exception) {
            return true;
        }
    }

    public static void runScheduled(String applicationName, Runnable task) {
        TraceContext traceContext = from(null);
        runWith(new RequestContext(traceContext.traceId(), childTraceparent(traceContext),
                null, null, null, applicationName), Map.of(MDC_TRACE_ID, traceContext.traceId()), task);
    }

    public static <T> T callScheduled(String applicationName, Callable<T> task) throws Exception {
        TraceContext traceContext = from(null);
        return callWith(new RequestContext(traceContext.traceId(), childTraceparent(traceContext),
                null, null, null, applicationName), Map.of(MDC_TRACE_ID, traceContext.traceId()), task);
    }

    public static Runnable wrap(Runnable task) {
        RequestContext context = RequestContextHolder.current().orElse(null);
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> runWith(context, mdcContext, task);
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        RequestContext context = RequestContextHolder.current().orElse(null);
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> callWith(context, mdcContext, task);
    }

    private static void runWith(RequestContext context, Map<String, String> mdcContext, Runnable task) {
        RequestContext previousContext = RequestContextHolder.current().orElse(null);
        Map<String, String> previousMdcContext = MDC.getCopyOfContextMap();
        install(context, mdcContext);
        try {
            task.run();
        } finally {
            restore(previousContext, previousMdcContext);
        }
    }

    private static <T> T callWith(RequestContext context, Map<String, String> mdcContext, Callable<T> task)
            throws Exception {
        RequestContext previousContext = RequestContextHolder.current().orElse(null);
        Map<String, String> previousMdcContext = MDC.getCopyOfContextMap();
        install(context, mdcContext);
        try {
            return task.call();
        } finally {
            restore(previousContext, previousMdcContext);
        }
    }

    private static void install(RequestContext context, Map<String, String> mdcContext) {
        if (context == null) {
            RequestContextHolder.clear();
        } else {
            RequestContextHolder.set(context);
        }
        if (mdcContext == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(mdcContext);
        }
    }

    private static void restore(RequestContext context, Map<String, String> mdcContext) {
        install(context, mdcContext);
    }

    private static boolean hasBlankSegment(String[] segments) {
        for (int index = 1; index < segments.length; index++) {
            if (segments[index].isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String newSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static boolean isAllZeros(String value) {
        return value.chars().allMatch(character -> character == '0');
    }

    public record TraceContext(String traceId, String traceFlags) {
    }
}

