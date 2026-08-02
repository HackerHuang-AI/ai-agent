package com.ai.agent.infrastructure.utils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * @Description: OkHttp 纯静态工具类，不依赖 Spring 容器。
 *               调用方需自行注入 {@code OkHttpConfig} 并通过其获取 {@link OkHttpClient} 后传入本类方法。
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: OkHttpUtil
 * @Author: HUANGcong
 * @Date: Created in 23:01 2026/4/12
 * @Version: 3.0
 */
@Slf4j
public class OkHttpUtil {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private OkHttpUtil() {}

    // ==================== 同步请求 ====================

    public static String get(OkHttpClient client, String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        return executeRequest(request, client);
    }

    public static String get(OkHttpClient client, String url, Map<String, String> headers) throws IOException {
        Request request = buildRequest(new Request.Builder().url(url).get(), headers).build();
        return executeRequest(request, client);
    }

    public static String post(OkHttpClient client, String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).post(body).build();
        return executeRequest(request, client);
    }

    public static String post(OkHttpClient client, String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request, client);
    }

    public static String postForm(OkHttpClient client, String url, Map<String, String> formParams) throws IOException {
        RequestBody body = buildFormBody(formParams);
        Request request = new Request.Builder().url(url).post(body).build();
        return executeRequest(request, client);
    }

    public static String postForm(OkHttpClient client, String url, Map<String, String> formParams, Map<String, String> headers) throws IOException {
        RequestBody body = buildFormBody(formParams);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request, client);
    }

    public static String put(OkHttpClient client, String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).put(body).build();
        return executeRequest(request, client);
    }

    public static String put(OkHttpClient client, String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).put(body), headers).build();
        return executeRequest(request, client);
    }

    public static String delete(OkHttpClient client, String url) throws IOException {
        Request request = new Request.Builder().url(url).delete().build();
        return executeRequest(request, client);
    }

    public static String delete(OkHttpClient client, String url, Map<String, String> headers) throws IOException {
        Request request = buildRequest(new Request.Builder().url(url).delete(), headers).build();
        return executeRequest(request, client);
    }

    // ==================== 异步请求 ====================

    public static CompletableFuture<String> getAsync(OkHttpClient client, String url) {
        Request request = new Request.Builder().url(url).get().build();
        return executeAsync(request, client);
    }

    public static CompletableFuture<String> getAsync(OkHttpClient client, String url, Map<String, String> headers) {
        Request request = buildRequest(new Request.Builder().url(url).get(), headers).build();
        return executeAsync(request, client);
    }

    public static CompletableFuture<String> postAsync(OkHttpClient client, String url, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder().url(url).post(body).build();
        return executeAsync(request, client);
    }

    public static CompletableFuture<String> postAsync(OkHttpClient client, String url, String jsonBody, Map<String, String> headers) {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeAsync(request, client);
    }

    // ==================== 底层执行 ====================

    public static String executeRequest(Request request, OkHttpClient client) throws IOException {
        long startTime = System.currentTimeMillis();
        try (Response response = client.newCall(request).execute()) {
            long costTime = System.currentTimeMillis() - startTime;
            if (!response.isSuccessful()) {
                log.warn("HTTP请求失败: {} {}, 状态码: {}, 耗时: {}ms",
                        request.method(), request.url(), response.code(), costTime);
                throw new IOException("HTTP请求失败: " + response.code() + " " + response.message());
            }
            ResponseBody responseBody = response.body();
            return responseBody != null ? responseBody.string() : "";
        } catch (IOException e) {
            long costTime = System.currentTimeMillis() - startTime;
            log.error("HTTP请求异常: {} {}, 耗时: {}ms, 错误: {}",
                    request.method(), request.url(), costTime, e.getMessage());
            throw e;
        }
    }

    public static Response executeRequestWithResponse(Request request, OkHttpClient client) throws IOException {
        return client.newCall(request).execute();
    }

    private static CompletableFuture<String> executeAsync(Request request, OkHttpClient client) {
        long startTime = System.currentTimeMillis();
        CompletableFuture<String> future = new CompletableFuture<>();
        client.newCall(request).enqueue(new Callback() {
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
                    future.complete(responseBody != null ? responseBody.string() : "");
                }
            }
        });
        return future;
    }

    // ==================== 工具方法 ====================

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

