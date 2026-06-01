package com.ai.agent.infrastructure.persistence.service;

import com.ai.agent.application.bo.LlmModelBO;
import com.ai.agent.application.common.BizException;
import com.ai.agent.application.enums.ErrorCodeEnum;
import com.ai.agent.application.service.LlmModelQueryService;
import com.ai.agent.infrastructure.persistence.do_.LlmApiKeyDO;
import com.ai.agent.infrastructure.persistence.do_.LlmModelDO;
import com.ai.agent.infrastructure.persistence.mapper.LlmApiKeyMapper;
import com.ai.agent.infrastructure.persistence.mapper.LlmModelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * @Description: LLM 模型配置查询服务实现，基于 MyBatis-Plus LambdaQuery 查询 DB
 *               API Key 解密：当前用 Base64 解码（开发阶段），生产替换为 AES 解密即可
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.persistence.service
 * @ClassName: LlmModelQueryServiceImpl
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmModelQueryServiceImpl implements LlmModelQueryService {

    private final LlmModelMapper llmModelMapper;
    private final LlmApiKeyMapper llmApiKeyMapper;

    @Override
    public LlmModelBO getEnabledModel(String modelCode) {
        LlmModelDO model = llmModelMapper.selectOne(
                new LambdaQueryWrapper<LlmModelDO>()
                        .eq(LlmModelDO::getModelCode, modelCode)
                        .orderByDesc(LlmModelDO::getId)
                        .last("LIMIT 1")
        );
        if (model == null) {
            log.warn("模型不存在或已禁用, modelCode={}", modelCode);
            throw new BizException(ErrorCodeEnum.LLM_MODEL_NOT_FOUND);
        }
        return toBO(model);
    }

    @Override
    public String getApiKey(String platformCode) {
        LlmApiKeyDO keyDO = llmApiKeyMapper.selectOne(
                new LambdaQueryWrapper<LlmApiKeyDO>()
                        .eq(LlmApiKeyDO::getPlatformCode, platformCode)
                        .orderByDesc(LlmApiKeyDO::getId)
                        .last("LIMIT 1")
        );
        if (keyDO == null || !StringUtils.hasText(keyDO.getApiKey())) {
            log.warn("平台 API Key 未配置, platformCode={}", platformCode);
            throw new BizException(ErrorCodeEnum.LLM_API_KEY_NOT_FOUND);
        }
        return decryptApiKey(keyDO.getApiKey());
    }

    // ==================== 私有方法 ====================

    private LlmModelBO toBO(LlmModelDO model) {
        return LlmModelBO.builder()
                .id(model.getId())
                .platformCode(model.getPlatformCode())
                .modelCode(model.getModelCode())
                .modelName(model.getModelName())
                .apiEndpoint(model.getApiEndpoint())
                .contextWindow(model.getContextWindow() != null ? model.getContextWindow() : 0)
                .rpmLimit(model.getRpmLimit() != null ? model.getRpmLimit() : 0)
                .supportStream(model.getSupportStream() != null && model.getSupportStream() == 1)
                .build();
    }

    /**
     * 解密 API Key
     * 当前：Base64 解码（开发阶段，DB 里直接存 Base64 编码的 Key）
     * 生产替换：AES-256-GCM 解密，密钥从 Vault 读取
     */
    private String decryptApiKey(String encryptedKey) {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedKey);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 解码失败说明 DB 里存的是明文（本地开发场景），直接返回
            log.debug("API Key 非 Base64 编码，按明文使用");
            return encryptedKey;
        }
    }
}

