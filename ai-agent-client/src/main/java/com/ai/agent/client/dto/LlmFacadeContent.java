package com.ai.agent.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
 *
 * @author HUANGcong
 * @since 2026-05-29
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

    /** 文本内容 */
    private String text;

    /** 图片地址及细节级别 */
    private UrlSource imageUrl;

    /** 输入音频来源 */
    private AudioSource inputAudio;

    /** 视频地址及帧率 */
    private VideoSource videoUrl;

    /** 文件来源 */
    private FileSource file;

    /** 文件地址 */
    private UrlSource fileUrl;

    /** 内容来源 */
    private ContentSource source;

    /** 视频帧率 */
    private Double fps;

    /** 内容资源标识 */
    private String uri;

    /** 内容数据 */
    private String data;

    /** 内容媒体类型 */
    private String mimeType;

    /** 地址来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UrlSource implements Serializable {
        /** 序列化版本标识 */
        private static final long serialVersionUID = 1L;
        /** 资源地址 */
        private String url;
        /** 细节级别 */
        private String detail;
    }

    /** 音频来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AudioSource implements Serializable {
        /** 序列化版本标识 */
        private static final long serialVersionUID = 1L;
        /** 音频数据 */
        private String data;
        /** 音频格式 */
        private String format;
        /** 音频地址 */
        private String url;
        /** 已上传文件标识 */
        private String fileId;
    }

    /** 视频来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VideoSource implements Serializable {
        /** 序列化版本标识 */
        private static final long serialVersionUID = 1L;
        /** 视频地址 */
        private String url;
        /** 已上传文件标识 */
        private String fileId;
        /** 视频帧率 */
        private Double fps;
    }

    /** 文件来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FileSource implements Serializable {
        /** 序列化版本标识 */
        private static final long serialVersionUID = 1L;
        /** 已上传文件标识 */
        private String fileId;
        /** 文件数据 */
        private String fileData;
        /** 文件地址 */
        private String fileUrl;
        /** 文件名称 */
        private String filename;
    }

    /** 内容来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContentSource implements Serializable {
        /** 序列化版本标识 */
        private static final long serialVersionUID = 1L;
        /** 来源类型 */
        private String type;
        /** 媒体类型 */
        private String mediaType;
        /** 内容数据 */
        private String data;
        /** 资源地址 */
        private String url;
        /** 已上传文件标识 */
        private String fileId;
    }

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
    /** 获取文本内容 */
    public String getText() { return text; }
    /** 设置文本内容 */
    public void setText(String text) { this.text = text; }
    /** 获取图片来源 */
    public UrlSource getImageUrl() { return imageUrl; }
    /** 设置图片来源 */
    public void setImageUrl(UrlSource imageUrl) { this.imageUrl = imageUrl; }
    /** 获取输入音频来源 */
    public AudioSource getInputAudio() { return inputAudio; }
    /** 设置输入音频来源 */
    public void setInputAudio(AudioSource inputAudio) { this.inputAudio = inputAudio; }
    /** 获取视频来源 */
    public VideoSource getVideoUrl() { return videoUrl; }
    /** 设置视频来源 */
    public void setVideoUrl(VideoSource videoUrl) { this.videoUrl = videoUrl; }
    /** 获取文件来源 */
    public FileSource getFile() { return file; }
    /** 设置文件来源 */
    public void setFile(FileSource file) { this.file = file; }
    /** 获取文件地址来源 */
    public UrlSource getFileUrl() { return fileUrl; }
    /** 设置文件地址来源 */
    public void setFileUrl(UrlSource fileUrl) { this.fileUrl = fileUrl; }
    /** 获取内容来源 */
    public ContentSource getSource() { return source; }
    /** 设置内容来源 */
    public void setSource(ContentSource source) { this.source = source; }
    /** 获取视频帧率 */
    public Double getFps() { return fps; }
    /** 设置视频帧率 */
    public void setFps(Double fps) { this.fps = fps; }
    /** 获取内容资源标识 */
    public String getUri() { return uri; }
    /** 设置内容资源标识 */
    public void setUri(String uri) { this.uri = uri; }
    /** 获取内容数据 */
    public String getData() { return data; }
    /** 设置内容数据 */
    public void setData(String data) { this.data = data; }
    /** 获取内容媒体类型 */
    public String getMimeType() { return mimeType; }
    /** 设置内容媒体类型 */
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}

