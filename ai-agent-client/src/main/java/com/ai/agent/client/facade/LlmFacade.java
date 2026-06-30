package com.ai.agent.client.facade;

import com.ai.agent.client.dto.LlmFacadeRequest;
import com.ai.agent.client.dto.LlmFacadeResponse;

/**
 * AI-Agent LLM 能力对外 Dubbo 接口
 *
 * <p>Consumer 侧（ai-knowledge、ai-analysis 等）通过 @DubboReference 注入此接口，
 * 即可调用 ai-agent 接入的所有 LLM 平台，无需感知底层实现。</p>
 *
 * <h3>使用示例（Consumer 端）</h3>
 * <pre>{@code
 * @DubboReference
 * private LlmFacade llmFacade;
 *
 * LlmFacadeRequest req = new LlmFacadeRequest();
 * req.setPlatform("doubao");
 * req.setModelCode("doubao-pro-32k");
 * req.setMessages(List.of(LlmFacadeMessage.user("你好")));
 *
 * LlmFacadeResponse resp = llmFacade.chat(req);
 * System.out.println(resp.getContent());
 * }</pre>
 */
public interface LlmFacade {

    /**
     * 同步调用指定平台的 LLM，返回完整响应
     *
     * @param request 请求参数，{@code platform} 字段必填，用于路由到对应平台 Service
     * @return 统一响应，{@code content} 为模型文本回复
     * @throws RuntimeException 平台不支持、调用失败时抛出
     */
    LlmFacadeResponse chat(LlmFacadeRequest request);
}

