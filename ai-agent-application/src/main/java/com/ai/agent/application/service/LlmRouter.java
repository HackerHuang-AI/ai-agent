package com.ai.agent.application.service;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmModelPage;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.model.llm.LlmResponsesRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM 平台路由器
 *
 * <p>通过 Spring 自动注入 {@code Map<String, LlmService>}（Bean 名称 → 实现类），
 * 根据 {@code platform} 参数路由到对应平台 Service，无需手动维护 switch。
 *
 * <p>重试逻辑在各 ServiceImpl.chat() 内部实现，紧贴 HTTP 调用层，
 * 避免在路由层重试时重复执行序列化、日志等无用操作。
 *
 * <p>平台编码为 PascalCase；新增平台时需确保其 Service Bean 名称可由本类解析。
 *
 * <h3>支持的 platform 编码</h3>
 * <pre>
 *   Doubao / OpenAI / Deepseek / Anthropic / Glm / Qwen
 *   Moonshot / MiniMax / Gemini / Ollama / Qianfan
 *   TokenHub / Mimo
 * </pre>
 */
@Slf4j
@Service
public class LlmRouter {

    /**
     * Spring 自动将所有 LlmService 实现类注入为 Map，key 为 Bean 名称。
     * Bean 名称由类名默认生成，首字母缩写平台通过 PLATFORM_BEAN_MAP 映射。
     */
    private final Map<String, LlmService> serviceMap;

    public LlmRouter(Map<String, LlmService> serviceMap) {
        this.serviceMap = serviceMap;
    }

    /**
     * 根据 platform 路由到对应 Service 执行同步调用。
     *
     * <p>重试由各 ServiceImpl 内部在 HTTP 调用层执行，本层不做重试。
     * 4xx 错误（客户端错误）由 ServiceImpl 内部识别并抛出 BizException，直接向上传递。
     *
     * @param platform 平台编码
     * @param request  已组装好的 LlmRequest
     * @return LLM 响应
     */
    public LlmResponse chat(String platform, LlmRequest request) {
        LlmService service = resolve(platform);
        log.info("[LlmRouter] platform={}, modelCode={}", platform, request.getModelCode());
        LlmResponse result = service.chat(request);
        if (result == null) {
            throw new BizException(ErrorCodeEnum.LLM_CALL_FAILED);
        }
        return result;
    }

    /**
     * 根据 platform 路由到对应 Service 执行 Responses API 调用。
     *
     * @param platform 平台编码
     * @param request  统一 Responses API 入参
     * @return 统一响应
     */
    public LlmResponse responses(String platform, LlmResponsesRequest request) {
        LlmService service = resolve(platform);
        log.info("[LlmRouter] responses platform={}, model={}", platform, request.getModel());
        try {
            return service.responses(request);
        } catch (UnsupportedOperationException e) {
            log.warn("[LlmRouter] platform={} 未接入 Responses API", platform);
            return LlmResponse.builder().modelCode(request.getModel()).output(java.util.List.of()).build();
        }
    }

    /**
     * 根据 platform 路由到对应 Service 查询模型列表。
     */
    public LlmModelPage listModels(String platform, String apiKey, int pageNo, int pageSize) {
        LlmService service = resolve(platform);
        log.info("[LlmRouter] listModels platform={}, pageNo={}, pageSize={}, apiKey={}",
                platform, pageNo, pageSize, apiKey == null ? "Nacos兜底" : "已传");
        return service.listModels(apiKey, pageNo, pageSize);
    }

    /**
     * 根据 platform 路由到对应 Service 执行流式调用
     *
     * @param platform      平台编码
     * @param request       已组装好的 LlmRequest
     * @param chunkConsumer 每个流式 chunk 的回调，null 表示正常结束，"[ERROR]" 表示出错结束
     */
    public void chatStream(String platform, LlmRequest request, Consumer<String> chunkConsumer) {
        LlmService service = resolve(platform);
        log.info("[LlmRouter] stream platform={}, modelCode={}", platform, request.getModelCode());
        service.chatStream(request, chunkConsumer);
    }

    /**
     * 平台编码与 Spring Bean 名的映射表。
     */
    private static final Map<String, String> PLATFORM_BEAN_MAP = Map.ofEntries(
            Map.entry("Doubao", "doubaoServiceImpl"),
            Map.entry("OpenAI", "openAIServiceImpl"),
            Map.entry("Deepseek", "deepseekServiceImpl"),
            Map.entry("Anthropic", "anthropicServiceImpl"),
            Map.entry("Glm", "glmServiceImpl"),
            Map.entry("Qwen", "qwenServiceImpl"),
            Map.entry("Moonshot", "moonshotServiceImpl"),
            Map.entry("MiniMax", "miniMaxServiceImpl"),
            Map.entry("Gemini", "geminiServiceImpl"),
            Map.entry("Ollama", "ollamaServiceImpl"),
            Map.entry("Qianfan", "qianfanServiceImpl"),
            Map.entry("TokenHub", "tokenHubServiceImpl"),
            Map.entry("Mimo", "mimoServiceImpl"));

    /**
     * 按平台编码映射到 Bean 名称后查找。
     */
    private LlmService resolve(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new BizException(ErrorCodeEnum.LLM_PLATFORM_NOT_SUPPORTED);
        }
        String beanName = PLATFORM_BEAN_MAP.get(platform);
        LlmService service = beanName == null ? null : serviceMap.get(beanName);
        if (service == null) {
            log.error("[LlmRouter] 不支持的平台: {}, beanName={}", platform, beanName);
            throw new BizException(ErrorCodeEnum.LLM_PLATFORM_NOT_SUPPORTED);
        }
        return service;
    }
}

