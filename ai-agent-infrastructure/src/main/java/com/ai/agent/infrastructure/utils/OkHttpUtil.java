package com.ai.agent.infrastructure.utils;

import com.ai.agent.infrastructure.config.OkHttpConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @Description: OkHttp 工具类，支持同步/异步请求，适用于高并发场景。
 *
 * <p>Client 参数（超时、连接池）由 {@link OkHttpConfig} 统一管理，支持 Nacos 热更新：
 * <ul>
 *   <li>通用请求使用 {@code getClient()} 返回的 Client（readTimeout=15s）</li>
 *   <li>LLM 请求使用 {@code getLlmClient()} 返回的 Client（readTimeout=120s）</li>
 * </ul>
 *
 * <p>静态方法通过 Spring 注入 {@link OkHttpConfig} 实例后委托调用，与 {@link NacosConfigUtil} 保持一致的设计风格。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: OkHttpUtil
 * @Author: HUANGcong
 * @Date: Created in 23:01 2026/4/12
 * @Version: 2.0
 */
@Slf4j
@Component
public class OkHttpUtil {

    private static OkHttpConfig okHttpConfig;

    @Autowired
    public void setOkHttpConfig(OkHttpConfig okHttpConfig) {
        OkHttpUtil.okHttpConfig = okHttpConfig;
    }

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private OkHttpUtil() {}

    // ==================== 获取 Client ====================

    /** 获取通用 OkHttpClient（readTimeout=15s，热更新后自动切换到最新实例） */
    public static OkHttpClient getClient() {
        return okHttpConfig.getClient();
    }

    /**
     * 获取 LLM 专用 OkHttpClient（使用全局 llm 超时配置，热更新后自动切换到最新实例）。
     * 需要平台专属超时时，请调用 {@link OkHttpConfig#getLlmClient(String)} 并传入 scope。
     */
    public static OkHttpClient getLlmClient() {
        return okHttpConfig.getLlmClient(null);
    }

    // ==================== 同步请求 ====================

    public static String get(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        return executeRequest(request);
    }

    public static String get(String url, Map<String, String> headers) throws IOException {
        Request request = buildRequest(new Request.Builder().url(url).get(), headers).build();
        return executeRequest(request);
    }

    public static String post(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).post(body).build();
        return executeRequest(request);
    }

    public static String post(String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request);
    }

    public static String postForm(String url, Map<String, String> formParams) throws IOException {
        RequestBody body = buildFormBody(formParams);
        Request request = new Request.Builder().url(url).post(body).build();
        return executeRequest(request);
    }

    public static String postForm(String url, Map<String, String> formParams, Map<String, String> headers) throws IOException {
        RequestBody body = buildFormBody(formParams);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request);
    }

    public static String put(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).put(body).build();
        return executeRequest(request);
    }

    public static String put(String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).put(body), headers).build();
        return executeRequest(request);
    }

    public static String delete(String url) throws IOException {
        Request request = new Request.Builder().url(url).delete().build();
        return executeRequest(request);
    }

    public static String delete(String url, Map<String, String> headers) throws IOException {
        Request request = buildRequest(new Request.Builder().url(url).delete(), headers).build();
        return executeRequest(request);
    }

    // ==================== 异步请求 ====================

    public static CompletableFuture<String> getAsync(String url) {
        Request request = new Request.Builder().url(url).get().build();
        return executeAsync(request);
    }

    public static CompletableFuture<String> getAsync(String url, Map<String, String> headers) {
        Request request = buildRequest(new Request.Builder().url(url).get(), headers).build();
        return executeAsync(request);
    }

    public static CompletableFuture<String> postAsync(String url, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).post(body).build();
        return executeAsync(request);
    }

    public static CompletableFuture<String> postAsync(String url, String jsonBody, Map<String, String> headers) {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeAsync(request);
    }

    // ==================== 底层执行 ====================

    private static String executeRequest(Request request) throws IOException {
        long startTime = System.currentTimeMillis();
        log.debug("发送HTTP请求: {} {}", request.method(), request.url());

        try (Response response = getClient().newCall(request).execute()) {
            long costTime = System.currentTimeMillis() - startTime;

            if (!response.isSuccessful()) {
                log.warn("HTTP请求失败: {} {}, 状态码: {}, 耗时: {}ms",
                        request.method(), request.url(), response.code(), costTime);
                throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
            }

            ResponseBody responseBody = response.body();
            String result = responseBody != null ? responseBody.string() : "";

            log.debug("HTTP请求成功: {} {}, 状态码: {}, 耗时: {}ms",
                    request.method(), request.url(), response.code(), costTime);
            return result;
        } catch (IOException e) {
            long costTime = System.currentTimeMillis() - startTime;
            log.error("HTTP请求异常: {} {}, 耗时: {}ms, 错误: {}",
                    request.method(), request.url(), costTime, e.getMessage());
            throw e;
        }
    }

    private static CompletableFuture<String> executeAsync(Request request) {
        long startTime = System.currentTimeMillis();
        log.debug("发送异步HTTP请求: {} {}", request.method(), request.url());

        CompletableFuture<String> future = new CompletableFuture<>();
        getClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                long costTime = System.currentTimeMillis() - startTime;
                log.error("异步HTTP请求异常: {} {}, 耗时: {}ms, 错误: {}",
                        request.method(), request.url(), costTime, e.getMessage());
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                long costTime = System.currentTimeMillis() - startTime;
                try (response) {
                    if (!response.isSuccessful()) {
                        log.warn("异步HTTP请求失败: {} {}, 状态码: {}, 耗时: {}ms",
                                request.method(), request.url(), response.code(), costTime);
                        future.completeExceptionally(
                                new IOException("HTTP请求失败: " + response.code() + " " + response.message())
                        );
                        return;
                    }
                    ResponseBody responseBody = response.body();
                    String result = responseBody != null ? responseBody.string() : "";
                    log.debug("异步HTTP请求成功: {} {}, 状态码: {}, 耗时: {}ms",
                            request.method(), request.url(), response.code(), costTime);
                    future.complete(result);
                }
            }
        });
        return future;
    }

    // ==================== 工具方法 ====================

    public static Response executeRequestWithResponse(Request request) throws IOException {
        log.debug("发送HTTP请求(返回Response): {} {}", request.method(), request.url());
        return getClient().newCall(request).execute();
    }

    private static Request.Builder buildRequest(Request.Builder builder, Map<String, String> headers) {
        if (headers != null && !headers.isEmpty()) {
            headers.forEach(builder::addHeader);
        }
        return builder;
    }

    private static RequestBody buildFormBody(Map<String, String> formParams) {
        FormBody.Builder formBuilder = new FormBody.Builder();
        if (formParams != null && !formParams.isEmpty()) {
            formParams.forEach(formBuilder::add);
        }
        return formBuilder.build();
    }
}

