package com.ai.agent.starter.controller.vo;

import com.ai.agent.application.enums.ContentTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * @Description: 对话消息中单条内容单元的 VO
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: ChatMessageVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/2
 * @Version: 1.0
 */
@Data
public class ChatMessageVO {

    /**
     * 消息角色：system / user / assistant
     */
    @NotBlank(message = "role 不能为空")
    private String role;

    /**
     * 内容类型，默认 TEXT
     * TEXT  → value 为纯文本
     * IMAGE → value 为图片 URL 或 "data:image/jpeg;base64,xxx"
     * FILE  → value 为 "data:application/pdf;base64,xxx"，detail 为文件名
     * VIDEO → value 为视频 URL 或 "data:video/mp4;base64,xxx"
     */
    @NotNull(message = "type 不能为空")
    private ContentTypeEnum type;

    /**
     * 内容值
     */
    @NotBlank(message = "value 不能为空")
    private String value;

    /**
     * 附加说明（可选）：
     * IMAGE → "low" / "high" / "auto"（默认 auto）
     * FILE  → 文件原始名称
     */
    private String detail;
}

