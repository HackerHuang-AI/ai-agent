package com.ai.agent.infrastructure.llm.service;

import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;

import java.util.function.Consumer;

/**
 * @Description: LLM 平台调用服务接口，每个平台实现此接口
 *               职责：将统一的 LlmRequest 转换为各平台协议格式，发起 HTTP 调用，解析响应为 LlmResponse
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.llm.service
 * @ClassName: LlmPlatformService
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
public interface LlmPlatformService {

    /**
     * 返回此 Service 支持的平台编码，对应 llm_platform.code
     * 例如：OPENAI、DEEPSEEK、DOUBAO
     */
    String platformCode();

    /**
     * 同步调用，等待模型完整响应后返回
     *
     * @param request  统一入参
     * @param apiKey   调用此平台使用的 API Key
     * @param endpoint 该平台的 API 地址
     * @return 统一响应
     */
    LlmResponse chat(LlmRequest request, String apiKey, String endpoint);

    /**
     * 流式调用，每收到一个 chunk 回调一次 chunkConsumer
     * 调用结束后 chunkConsumer 收到 null 表示流结束
     *
     * @param request       统一入参
     * @param apiKey        API Key
     * @param endpoint      API 地址
     * @param chunkConsumer 每个流式 chunk 的回调，null 表示流结束
     */
    void chatStream(LlmRequest request, String apiKey, String endpoint, Consumer<String> chunkConsumer);
}

