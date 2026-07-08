package com.ai.agent.application.service;

import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;

import java.util.function.Consumer;

/**
 * @Description: LLM 统一调用服务接口
 *               职责：根据 modelCode 查询模型配置，路由到对应平台 Adapter，完成调用并持久化记录
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service
 * @ClassName: LlmService
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
public interface LlmService {

    /**
     * 同步调用 LLM，等待完整响应后返回
     *
     * @param request 统一入参，modelCode 不可为空
     * @return 统一响应
     */
    LlmResponse chat(LlmRequest request);

    /**
     * 流式调用 LLM，每收到一个 chunk 触发一次回调。
     *
     * <p>结束信号约定：
     * <ul>
     *   <li>{@code null}      — 正常结束（流读完或 SSE [DONE]）</li>
     *   <li>{@code "[ERROR]"} — 出错结束（HTTP 失败 / IO 异常 / 业务异常）</li>
     *   <li>其他字符串       — 正常 chunk，直接透传给调用方</li>
     * </ul>
     *
     * @param request       统一入参，stream 字段会被强制设为 true
     * @param chunkConsumer 每个流式 chunk 的回调
     */
    void chatStream(LlmRequest request, Consumer<String> chunkConsumer);
}

