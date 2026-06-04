package com.ai.agent.starter.controller.vo.deepseek;

import com.ai.agent.application.enums.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Description: Deepseek 平台对话消息 VO
 *               支持多模态：TEXT / IMAGE（deepseek-chat），deepseek-reasoner 仅支持 TEXT
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.deepseek
 * @ClassName: DeepseekMessageVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Data
public class DeepseekMessageVO {

    /**
     * 消息角色：system / user / assistant
     */
    @NotBlank(message = "role 不能为空")
    private String role;

    /**
     * 内容类型：TEXT / IMAGE（FILE 和 VIDEO 不支持）
     */
    @NotNull(message = "type 不能为空")
    private ContentType type;

    /**
     * 内容值：TEXT 为文本，IMAGE 为 URL 或 base64 data URI
     */
    @NotBlank(message = "value 不能为空")
    private String value;

    /**
     * 附加说明（可选）：IMAGE 时为 "low" / "high" / "auto"（默认 auto）
     */
    private String detail;
}

