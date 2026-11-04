package com.ai.agent.starter.vo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 分页请求基类 VO，所有需要分页的请求 VO 均继承此类。
 * @author HUANGcong
 * @since 2026-04-20
 */
@Data
public class BasePageInVO {

    @NotNull(message = "2001010")
    @Min(value = 1, message = "2001010")
    private Integer pageNo = 1;
    @NotNull(message = "2001011")
    @Min(value = 1, message = "2001011")
    @Max(value = 100, message = "2001011")
    private Integer pageSize = 10;
}

