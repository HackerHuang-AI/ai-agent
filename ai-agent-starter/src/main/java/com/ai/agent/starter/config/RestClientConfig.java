package com.ai.agent.starter.config;

import com.ai.agent.application.model.request.RequestContext;
import com.ai.agent.application.model.request.RequestContextHolder;
import com.ai.agent.application.common.TraceContextSupport;
import com.ai.agent.starter.handler.TraceIdInterceptorHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/**
 * @Description: RestClient配置（基于JDK21原生HttpClient）
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.config
 * @ClassName: RestClientConfig
 * @Author: HUANGcong
 * @Date: Created in 2026/5/29
 * @Version: 1.0
 */
@Configuration
@RequiredArgsConstructor
public class RestClientConfig implements WebMvcConfigurer {
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String USER_ID_HEADER = "X-Internal-User-Id";
    private static final String TENANT_ID_HEADER = "X-Internal-Tenant-Id";
    private static final String SESSION_ID_HEADER = "X-Internal-Session-Id";
    private static final String CALL_CHAIN_HEADER = "X-Call-Chain";

    private final TraceIdInterceptorHandler traceIdInterceptorHandler;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(traceIdInterceptorHandler).addPathPatterns("/**").order(0);
    }

    /**
     * 基于 Accept-Language 请求头解析语言，默认中文。
     * GlobalExceptionHandler 依赖此 Bean 进行 i18n message 解析。
     */
    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(Locale.SIMPLIFIED_CHINESE);
        return resolver;
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    public RestClient restClient(HttpClient httpClient) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Content-Type", "application/json")
                .requestInterceptor((request, body, execution) -> {
                    RequestContextHolder.current().ifPresent(context -> propagate(request, context));
                    return execution.execute(request, body);
                })
                .build();
    }

    private void propagate(org.springframework.http.HttpRequest request, RequestContext context) {
        request.getHeaders().set(TRACEPARENT_HEADER, TraceContextSupport.childTraceparent(context.traceparent()));
        setHeader(request, USER_ID_HEADER, context.userId());
        setHeader(request, TENANT_ID_HEADER, context.tenantId());
        setHeader(request, SESSION_ID_HEADER, context.sessionId());
        setHeader(request, CALL_CHAIN_HEADER, context.callChain());
    }

    private void setHeader(org.springframework.http.HttpRequest request, String name, String value) {
        if (value != null && !value.isBlank()) {
            request.getHeaders().set(name, value);
        }
    }
}

