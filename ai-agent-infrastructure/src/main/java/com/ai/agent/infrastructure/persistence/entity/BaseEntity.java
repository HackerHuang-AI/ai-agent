package com.ai.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 持久化实体公共字段基类。 */
@Getter
@Setter
public abstract class BaseEntity {
    /** 主键 ID。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 创建时间。 */
    private LocalDateTime ctime;
    /** 更新时间。 */
    private LocalDateTime utime;
    /** 有效标识：1-有效，0-无效。 */
    @TableLogic(value = "1", delval = "0")
    private Integer valid;
}

