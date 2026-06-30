package com.ai.agent.application.service;

import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
import com.ai.agent.application.service.impl.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LLM 平台路由器
 *
 * <p>根据调用方传入的 {@code platform} 字段，将请求路由到对应的平台 Service。
 * 供 {@link com.ai.agent.starter.provider.LlmFacadeImpl}（Dubbo Provider）等内部入口统一使用。</p>
 *
 * <h3>支持的 platform 值（不区分大小写）</h3>
 * <pre>
 *   doubao / openai / deepseek / anthropic / zhipu / qwen
 *   moonshot / minimax / gemini / ollama / qianfan
 *   tokenhub / mimo / ds_tokenplan / hy_tokenplan
 * </pre>
 */
@Slf4j
@Service
public class LlmRouter {

    private final DoubaoServiceImpl      doubaoService;
    private final OpenAiServiceImpl      openAiService;
    private final DeepseekServiceImpl    deepseekService;
    private final AnthropicServiceImpl   anthropicService;
    private final ZhipuServiceImpl       zhipuService;
    private final QwenServiceImpl        qwenService;
    private final MoonshotServiceImpl    moonshotService;
    private final MinimaxServiceImpl     minimaxService;
    private final GeminiServiceImpl      geminiService;
    private final OllamaServiceImpl      ollamaService;
    private final QianfanServiceImpl     qianfanService;
    private final TokenhubServiceImpl    tokenhubService;
    private final MimoServiceImpl        mimoService;
    private final DsTokenPlanServiceImpl dsTokenPlanService;
    private final HyTokenPlanServiceImpl hyTokenPlanService;

    public LlmRouter(
            DoubaoServiceImpl doubaoService,
            OpenAiServiceImpl openAiService,
            DeepseekServiceImpl deepseekService,
            AnthropicServiceImpl anthropicService,
            ZhipuServiceImpl zhipuService,
            QwenServiceImpl qwenService,
            MoonshotServiceImpl moonshotService,
            MinimaxServiceImpl minimaxService,
            GeminiServiceImpl geminiService,
            OllamaServiceImpl ollamaService,
            QianfanServiceImpl qianfanService,
            TokenhubServiceImpl tokenhubService,
            MimoServiceImpl mimoService,
            DsTokenPlanServiceImpl dsTokenPlanService,
            HyTokenPlanServiceImpl hyTokenPlanService) {
        this.doubaoService      = doubaoService;
        this.openAiService      = openAiService;
        this.deepseekService    = deepseekService;
        this.anthropicService   = anthropicService;
        this.zhipuService       = zhipuService;
        this.qwenService        = qwenService;
        this.moonshotService    = moonshotService;
        this.minimaxService     = minimaxService;
        this.geminiService      = geminiService;
        this.ollamaService      = ollamaService;
        this.qianfanService     = qianfanService;
        this.tokenhubService    = tokenhubService;
        this.mimoService        = mimoService;
        this.dsTokenPlanService = dsTokenPlanService;
        this.hyTokenPlanService = hyTokenPlanService;
    }

    /**
     * 根据 platform 路由到对应 Service 执行同步调用
     *
     * @param platform platform 标识（不区分大小写）
     * @param request  已组装好的 LlmRequest
     * @return LLM 响应
     */
    public LlmResponse chat(String platform, LlmRequest request) {
        LlmService service = resolve(platform);
        log.info("[LlmRouter] platform={}, modelCode={}", platform, request.getModelCode());
        return service.chat(request);
    }

    /**
     * 将 platform 字符串映射到具体 Service 实例
     */
    private LlmService resolve(String platform) {
        if (platform == null) {
            throw new IllegalArgumentException("platform 不能为空");
        }
        return switch (platform.toLowerCase()) {
            case "doubao"       -> doubaoService;
            case "openai"       -> openAiService;
            case "deepseek"     -> deepseekService;
            case "anthropic"    -> anthropicService;
            case "zhipu"        -> zhipuService;
            case "qwen"         -> qwenService;
            case "moonshot"     -> moonshotService;
            case "minimax"      -> minimaxService;
            case "gemini"       -> geminiService;
            case "ollama"       -> ollamaService;
            case "qianfan"      -> qianfanService;
            case "tokenhub"     -> tokenhubService;
            case "mimo"         -> mimoService;
            case "ds_tokenplan" -> dsTokenPlanService;
            case "hy_tokenplan" -> hyTokenPlanService;
            default -> throw new IllegalArgumentException("不支持的平台: " + platform);
        };
    }
}

