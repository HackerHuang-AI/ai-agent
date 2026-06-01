package com.ai.agent.infrastructure.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Description: llm_api_key 表的数据库实体对象
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.persistence.do_
 * @ClassName: LlmApiKeyDO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Data
@TableName("llm_api_key")
public class LlmApiKeyDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String platformCode;
    private String apiKey;
    private String keyAlias;
    private String keyScope;
    private Long createUserId;
    private LocalDateTime createTime;
    private Long updateUserId;
    private LocalDateTime updateTime;
    @TableLogic
    private Byte valid;
    private Integer version;
}

