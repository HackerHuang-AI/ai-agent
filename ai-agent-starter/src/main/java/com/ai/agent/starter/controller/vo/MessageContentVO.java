package com.ai.agent.starter.controller.vo;

import com.ai.agent.application.enums.ContentTypeEnum;
import lombok.Data;

/**
 * @Description: 多模态消息内容块 VO，用于 LlmMessageVO.contents 字段。
 *               一条消息可包含多个内容块（如图片 + 文字混合），每个块对应一个 ContentVO。
 *               字段均非必填，由 Service 层根据业务场景校验。
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: MessageContentVO
 * @Author: HUANGcong
 * @Date: Created in 2026/7/10
 * @Version: 1.0
 */
@Data
public class MessageContentVO {

    /** 内容类型：TEXT / IMAGE / FILE / VIDEO，Service 层校验非空 */
    private ContentTypeEnum type;

    /** 内容值：TEXT 为文本，IMAGE 为 URL 或 base64 data URI，Service 层校验非空 */
    private String value;

    /** 附加说明（可选）：IMAGE 时为图片质量 "low" / "high" / "auto"（默认 auto） */
    private String detail;
}

