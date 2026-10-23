package com.ai.agent.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

/** 需使用乐观锁的业务实体基类。 */
@Getter
@Setter
public abstract class VersionedEntity extends BaseEntity {
    /** 乐观锁版本号。 */
    @Version
    private Integer version;
}

