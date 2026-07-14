package com.ai.agent.client.facade;

import com.ai.agent.client.dto.LlmFacadeRequest;
import com.ai.agent.client.dto.LlmFacadeResponse;
import org.apache.dubbo.common.stream.StreamObserver;

/**
 * AI-Agent LLM 能力对外 Dubbo 接口（Triple 协议）
 *
 * <p>Consumer 侧（ai-knowledge、ai-analysis 等）通过 {@code @DubboReference} 注入此接口，
 * 即可调用 ai-agent 接入的所有 LLM 平台，无需感知底层实现。</p>
 *
 * <h3>同步调用示例</h3>
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
 *
 * <h3>流式调用示例</h3>
 * <pre>{@code
 * llmFacade.chatStream(req, new StreamObserver<String>() {
 *     public void onNext(String chunk) {
 *         System.out.print(chunk);   // 逐 token 输出
 *     }
 *     public void onError(Throwable t) {
 *         log.error("stream error", t);
 *     }
 *     public void onCompleted() {
 *         System.out.println("\n[done]");
 *     }
 * });
 * }</pre>
 *
 * <p>⚠️ 流式接口需使用 Triple 协议（{@code protocol: tri}），Dubbo 2.x 不支持。</p>
 */
public interface LlmFacade {

    /**
     * 同步调用指定平台的 LLM，等待模型生成完毕后一次性返回完整响应。
     *
     * @param request 请求参数，{@code platform} 字段必填，用于路由到对应平台 Service
     * @return 统一响应，{@code content} 为模型文本回复
     * @throws RuntimeException 平台不支持、调用失败时抛出
     */
    LlmFacadeResponse chat(LlmFacadeRequest request);

    /**
     * 流式调用指定平台的 LLM（Triple Server Streaming），逐 token 推送给 Consumer。
     *
     * <p>底层通过 HTTP/2 Server Streaming 实现，Consumer 无需等待完整响应，
     * 每收到一个 chunk 即触发 {@link StreamObserver#onNext(Object)}。</p>
     *
     * <p>chunk 格式：模型增量文本（delta content），空字符串表示该 chunk 无文本内容（如纯 reasoning）。</p>
     *
     * @param request          请求参数，与 {@link #chat} 相同
     * @param responseObserver Consumer 端实现的回调：
     *                         {@code onNext(chunk)} — 收到一个文本片段；
     *                         {@code onError(t)}    — 发生错误，流终止；
     *                         {@code onCompleted()} — 流正常结束
     */
    void chatStream(LlmFacadeRequest request, StreamObserver<String> responseObserver);
}

