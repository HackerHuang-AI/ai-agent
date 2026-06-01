package com.ai.agent.application.service;

import com.ai.agent.application.bo.LlmModelBO;

/**
 * @Description: LLM 模型配置查询服务，封装对 llm_model 和 llm_api_key 表的查询
 *               实现类位于 infrastructure 层（依赖 MyBatis Mapper），此处仅定义契约
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.service
 * @ClassName: LlmModelQueryService
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
public interface LlmModelQueryService {

    /**
     * 查询已启用的模型配置
     *
     * @param modelCode 模型标识，如 gpt-4.1
     * @return 模型 BO
     * @throws com.ai.agent.application.common.BizException LLM_MODEL_NOT_FOUND 模型不存在或已禁用
     */
    LlmModelBO getEnabledModel(String modelCode);

    /**
     * 查询指定平台的已启用 API Key
     *
     * @param platformCode 平台编码，如 OPENAI
     * @return 解密后的 API Key 明文
     * @throws com.ai.agent.application.common.BizException LLM_API_KEY_NOT_FOUND Key 未配置
     */
    String getApiKey(String platformCode);
}

