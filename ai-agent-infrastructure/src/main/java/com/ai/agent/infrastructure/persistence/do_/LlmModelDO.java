package com.ai.agent.infrastructure.persistence.do_;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Description: llm_model 表的数据库实体对象
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.infrastructure.persistence.do_
 * @ClassName: LlmModelDO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/1
 * @Version: 1.0
 */
@Data
@TableName("llm_model")
public class LlmModelDO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String platformCode;
    private String modelCode;
    private String modelName;
    private String apiEndpoint;
    private Integer contextWindow;
    private Integer rpmLimit;
    private Byte supportText;
    private Byte supportVision;
    private Byte supportJson;
    private Byte supportTool;
    private Byte supportStream;
    private Byte isFeatured;
    private Integer sortOrder;
    private Long createUserId;
    private LocalDateTime createTime;
    private Long updateUserId;
    private LocalDateTime updateTime;
    @TableLogic
    private Byte valid;
    private Integer version;
}

