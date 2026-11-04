package com.ai.agent.starter.vo;

import com.ai.agent.application.enums.ContentTypeEnum;
import com.ai.agent.application.model.llm.MessageContent;
import lombok.Data;

/**
 * 多模态消息内容块 VO，用于 LlmMessageVO.contents 字段。
 *               一条消息可包含多个内容块（如图片 + 文字混合），每个块对应一个 ContentVO。
 *               字段均非必填，由 Service 层根据业务场景校验；平台及协议标注以当前代码使用范围为准。
 * @author HUANGcong
 * @since 2026-07-10
 */
@Data
public class MessageContentVO {

    /** 内容类型：TEXT / IMAGE / FILE / VIDEO，Service 层校验非空 */
    private ContentTypeEnum type;

    /** 内容值：TEXT 为文本，IMAGE 为 URL 或 base64 data URI，Service 层校验非空 */
    private String value;

    /** 附加说明（可选）：IMAGE 时为图片质量 "low" / "high" / "auto"（默认 auto） */
    private String detail;

    /** 文本内容 */
    private String text;

    /** 图片地址及细节级别 */
    private MessageContent.UrlSource imageUrl;

    /** OpenAI / GLM / Gemini / Doubao / Qwen Chat 输入音频来源，各子字段范围见 MessageContent.AudioSource。 */
    private MessageContent.AudioSource inputAudio;

    /** Qwen / Doubao / GLM Chat 视频来源，各子字段范围见 MessageContent.VideoSource。 */
    private MessageContent.VideoSource videoUrl;

    /** OpenAI / Doubao / GLM Chat 文件来源，各子字段范围见 MessageContent.FileSource。 */
    private MessageContent.FileSource file;

    /** GLM Chat 专用：历史 file_url 内容块的文件地址。 */
    private MessageContent.UrlSource fileUrl;

    /** Anthropic Messages 专用：image / document 内容块的 source。 */
    private MessageContent.ContentSource source;

    /** Qwen Chat 专用：内容块顶层视频帧率；Doubao Chat 使用 videoUrl.fps。 */
    private Double fps;

    /** Gemini 原生 Interactions 专用：多模态资源 URI，Chat 不使用。 */
    private String uri;

    /** Gemini 原生 Interactions 专用：多模态裸 Base64 数据，Chat 不使用。 */
    private String data;

    /** Gemini 原生 Interactions 专用：多模态 MIME 类型，Chat 不使用。 */
    private String mimeType;
}

