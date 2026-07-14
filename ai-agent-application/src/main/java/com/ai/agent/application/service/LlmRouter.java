package com.ai.agent.application.service;

import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.model.llm.LlmRequest;
import com.ai.agent.application.model.llm.LlmResponse;
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
 * <p>新增平台只需在对应 ServiceImpl 上添加 {@code @Service("xxx")} 注解即可，本类无需改动。
 *
 * <h3>支持的 platform 值（对应各 ServiceImpl 的 Bean 名称后缀，不区分大小写）</h3>
 * <pre>
 *   doubao / openai / deepseek / anthropic / zhipu / qwen
 *   moonshot / minimax / gemini / ollama / qianfan
 *   tokenhub / mimo / ds_tokenplan / hy_tokenplan
 * </pre>
 */
@Slf4j
@Service
public class LlmRouter {

    /**
     * Spring 自动将所有 LlmService 实现类注入为 Map，key 为 Bean 名称。
     * 各 ServiceImpl 的 Bean 名称须与 platform 值对应（见各类 @Service 注解）。
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
     * @param platform platform 标识（不区分大小写）
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
     * 根据 platform 路由到对应 Service 执行多模态调用。
     *
     * <p>若平台不支持多模态，对应 Service 的 default 实现会打印日志并返回 {@code null}，
     * 本方法将 {@code null} 原样返回，由 Controller 层决定如何处理。
     *
     * @param platform platform 标识（不区分大小写）
     * @param request  统一入参，messages.contents 中含图片等多模态内容
     * @return 统一响应；平台不支持时返回 {@code null}
     */
    public LlmResponse multimodalChat(String platform, LlmRequest request) {
        LlmService service = resolve(platform);
        log.info("[LlmRouter] multimodal platform={}, modelCode={}", platform, request.getModelCode());
        return service.multimodalChat(request);
    }

    /**
     * 根据 platform 路由到对应 Service 执行流式调用
     *
     * @param platform      platform 标识（不区分大小写）
     * @param request       已组装好的 LlmRequest
     * @param chunkConsumer 每个流式 chunk 的回调，null 表示正常结束，"[ERROR]" 表示出错结束
     */
    public void chatStream(String platform, LlmRequest request, Consumer<String> chunkConsumer) {
        LlmService service = resolve(platform);
        log.info("[LlmRouter] stream platform={}, modelCode={}", platform, request.getModelCode());
        service.chatStream(request, chunkConsumer);
    }

    /**
     * platform 值与 Spring Bean 名的特殊映射表。
     * 原因：ds_tokenplan / hy_tokenplan 含下划线，拼接后无法匹配驼峰 Bean 名，需显式映射。
     * 其余平台 Bean 名 = platform.toLowerCase() + "ServiceImpl"，无需在此声明。
     */
    private static final Map<String, String> PLATFORM_BEAN_MAP = Map.of(
            "ds_tokenplan", "dsTokenPlanServiceImpl"
    );

    /**
     * 将 platform 映射到 Bean 名称后查找。
     * 优先查特殊映射表；未命中则按 "platform + ServiceImpl" 通用规则拼接。
     */
    private LlmService resolve(String platform) {
        if (platform == null || platform.isBlank()) {
            throw new BizException(ErrorCodeEnum.LLM_PLATFORM_NOT_SUPPORTED);
        }
        String key = platform.toLowerCase();
        String beanName = PLATFORM_BEAN_MAP.getOrDefault(key, key + "ServiceImpl");
        LlmService service = serviceMap.get(beanName);
        if (service == null) {
            log.error("[LlmRouter] 不支持的平台: {}, beanName={}", platform, beanName);
            throw new BizException(ErrorCodeEnum.LLM_PLATFORM_NOT_SUPPORTED);
        }
        return service;
    }
}

