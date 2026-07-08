package com.ai.agent.starter.controller.vo;

import com.ai.agent.application.enums.ContentTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Description: LLM 统一对话消息 VO，所有平台共用
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: LlmMessageVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/28
 * @Version: 1.0
 */
@Data
public class LlmMessageVO {

    /** 消息角色：system / user / assistant */
    @NotBlank(message = "role 不能为空")
    private String role;

    /** 内容类型：TEXT / IMAGE */
    @NotNull(message = "type 不能为空")
    private ContentTypeEnum type;

    /** 内容值：TEXT 为文本，IMAGE 为 URL 或 base64 data URI */
    @NotBlank(message = "value 不能为空")
    private String value;

    /**
     * 附加说明（可选）：IMAGE 时为 "low" / "high" / "auto"（默认 auto）
     */
    private String detail;
}

