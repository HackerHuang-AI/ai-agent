package com.ai.agent.application.service;

import com.ai.agent.application.model.llm.LlmModelPage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.LlmResponsesRequest;

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

    /**
     * 调用厂商的 Responses API。
     *
     * <p>Responses API 与 Chat / Messages 是独立协议，使用 {@code input} 而非
     * {@code messages} 组织输入。未接入该协议的平台直接拒绝调用。
     *
     * @param request 统一 Responses API 入参
     * @return 统一响应
     */
    default LlmResponse responses(LlmResponsesRequest request) {
        throw new UnsupportedOperationException("当前平台未接入 Responses API");
    }

    /**
     * 查询平台支持的模型列表并返回统一分页结果。
     *
     * <p>各平台必须显式实现本方法：有官方模型列表接口时仅调用本平台接口；
     * 未确认或暂不支持时返回空分页结果并记录原因日志，禁止通过聚合平台查询其他厂商模型。
     * pageSize 最大为 20；天然全量返回的平台由各实现内部切片组装分页结果。
     *
     * @param apiKey API Key；为空时从 Nacos 兜底
     * @param pageNo 页码，从 1 开始
     * @param pageSize 每页数量，最大 20
     * @return 统一模型分页结果
     */
    LlmModelPage listModels(String apiKey, int pageNo, int pageSize);
}

