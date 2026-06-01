package com.ai.agent.infrastructure.persistence.mapper;

import com.ai.agent.infrastructure.persistence.do_.LlmApiKeyDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Description: llm_api_key 表 Mapper，继承 BaseMapper 获得通用 CRUD 能力
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.persistence.mapper
 * @ClassName: LlmApiKeyMapper
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Mapper
public interface LlmApiKeyMapper extends BaseMapper<LlmApiKeyDO> {
}

