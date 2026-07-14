package com.ai.agent.client.dto;

import java.io.Serializable;

/**
 * 多模态消息内容块，对应 {@link LlmFacadeMessage#getContents()} 中的单个元素。
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>纯文本：{@code LlmFacadeContent.ofText("你好")}</li>
 *   <li>图片 URL：{@code LlmFacadeContent.ofImageUrl("https://...")} </li>
 *   <li>图片 Base64：{@code LlmFacadeContent.ofImageBase64("data:image/jpeg;base64,...")}</li>
 * </ul>
 *
 * <h3>type 取值</h3>
 * <ul>
 *   <li>{@code text}  — 纯文本，{@link #value} 为文本内容</li>
 *   <li>{@code image} — 图片，{@link #value} 为 URL 或 data URI（data:image/jpeg;base64,xxx）</li>
 *   <li>{@code file}  — 文档文件，{@link #value} 为 data URI，{@link #detail} 为原始文件名</li>
 *   <li>{@code video} — 视频，{@link #value} 为 URL 或 data URI</li>
 * </ul>
 */
public class LlmFacadeContent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 内容类型：text / image / file / video
     */
    private String type;

    /**
     * 内容值：
     * <ul>
     *   <li>text  → 文本字符串</li>
     *   <li>image → 图片 URL 或 data URI（{@code data:image/jpeg;base64,xxx}）</li>
     *   <li>file  → data URI（{@code data:application/pdf;base64,xxx}）</li>
     *   <li>video → 视频 URL 或 data URI</li>
     * </ul>
     */
    private String value;

    /**
     * 附加说明（可选）：
     * <ul>
     *   <li>image → 图片细节级别 {@code "low"} / {@code "high"} / {@code "auto"}（对应 OpenAI detail 参数）</li>
     *   <li>file  → 文件原始名称（用于日志与平台展示）</li>
     * </ul>
     */
    private String detail;

    public LlmFacadeContent() {}

    private LlmFacadeContent(String type, String value, String detail) {
        this.type = type;
        this.value = value;
        this.detail = detail;
    }

    /** 纯文本内容块 */
    public static LlmFacadeContent ofText(String text) {
        return new LlmFacadeContent("text", text, null);
    }

    /** 图片内容块（URL 或 data URI 均可） */
    public static LlmFacadeContent ofImage(String urlOrDataUri) {
        return new LlmFacadeContent("image", urlOrDataUri, null);
    }

    /** 图片内容块，并指定 detail 精度 */
    public static LlmFacadeContent ofImage(String urlOrDataUri, String detail) {
        return new LlmFacadeContent("image", urlOrDataUri, detail);
    }

    /** 文件内容块（data URI + 文件名） */
    public static LlmFacadeContent ofFile(String dataUri, String fileName) {
        return new LlmFacadeContent("file", dataUri, fileName);
    }

    /** 视频内容块（URL 或 data URI） */
    public static LlmFacadeContent ofVideo(String urlOrDataUri) {
        return new LlmFacadeContent("video", urlOrDataUri, null);
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}

