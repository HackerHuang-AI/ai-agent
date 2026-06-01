package com.ai.agent.infrastructure.utils;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @Description: OkHttp工具类，支持同步/异步请求，适用于高并发场景
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.utils
 * @ClassName: OkHttpUtil
 * @Author: HUANGcong
 * @Date: Created in 23:01 2026/4/12
 * @Version: 1.0
 */
@Slf4j
public class OkHttpUtil {

    private static final OkHttpClient CLIENT;

    /**
     * LLM 专用 Client：readTimeout 放宽至 120s，适应大模型慢响应场景
     * LLM 调用（GPT-4、Claude 等）响应时间可达 30-60s，通用 CLIENT 的 15s 会导致大量超时
     */
    private static final OkHttpClient LLM_CLIENT;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    static {
        // 高并发场景：最大空闲连接数50，保活时间5分钟
        ConnectionPool connectionPool = new ConnectionPool(
                50,
                5,
                TimeUnit.MINUTES
        );

        CLIENT = new OkHttpClient.Builder()
                .connectionPool(connectionPool)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .protocols(java.util.Arrays.asList(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .build();

        // LLM 专用：共用连接池，只覆盖超时配置
        LLM_CLIENT = CLIENT.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        log.info("OkHttpClient初始化完成，连接池配置: 最大空闲连接数=50, 保活时间=5分钟, 优先HTTP/2");
    }

    private OkHttpUtil() {
    }

    // ==================== 同步请求 ====================

    public static String get(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return executeRequest(request);
    }

    public static String get(String url, Map<String, String> headers) throws IOException {
        Request request = buildRequest(new Request.Builder().url(url).get(), headers).build();
        return executeRequest(request);
    }

    public static String post(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        return executeRequest(request);
    }

    public static String post(String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request);
    }

    public static String postForm(String url, Map<String, String> formParams) throws IOException {
        RequestBody body = buildFormBody(formParams);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        return executeRequest(request);
    }

    public static String postForm(String url, Map<String, String> formParams, Map<String, String> headers) throws IOException {
        RequestBody body = buildFormBody(formParams);
        Request request = buildRequest(new Request.Builder().url(url).post(body), headers).build();
        return executeRequest(request);
    }

    public static String put(String url, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(url)
                .put(body)
                .build();
        return executeRequest(request);
    }

    public static String put(String url, String jsonBody, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
        Request request = buildRequest(new Request.Builder().url(url).put(body), headers).build();
        return executeRequest(request);
    }

    public static String delete(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();
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

        try (Response response = CLIENT.newCall(request).execute()) {
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
        CLIENT.newCall(request).enqueue(new Callback() {
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
        return CLIENT.newCall(request).execute();
    }

    public static OkHttpClient getClient() {
        return CLIENT;
    }

    /** 返回 LLM 专用 Client（readTimeout=120s） */
    public static OkHttpClient getLlmClient() {
        return LLM_CLIENT;
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

