package com.ai.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 * Responses API 输入内容块。
 *
 * @author HUANGcong
 * @since 2026-05-29
 */
public class LlmFacadeResponsesContent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String type;
    private String text;
    @JsonProperty("image_url")
    @JsonAlias("imageUrl")
    private String imageUrl;
    @JsonProperty("file_id")
    @JsonAlias("fileId")
    private String fileId;
    private String detail;

    /** 文件地址 */
    @JsonProperty("file_url")
    @JsonAlias("fileUrl")
    private String fileUrl;

    /** 文件数据 */
    @JsonProperty("file_data")
    @JsonAlias("fileData")
    private String fileData;

    /** 文件名称 */
    private String filename;

    /** 音频地址 */
    @JsonProperty("audio_url")
    @JsonAlias("audioUrl")
    private String audioUrl;

    /** 视频地址 */
    @JsonProperty("video_url")
    @JsonAlias("videoUrl")
    private String videoUrl;

    /** 内容数据 */
    private String data;

    /** 音视频格式 */
    private String format;

    /** 视频帧率 */
    private Double fps;

    /** 拒绝响应文本 */
    private String refusal;

    /** 内容资源标识 */
    private String uri;

    /** 内容媒体类型 */
    @JsonProperty("mime_type")
    @JsonAlias("mimeType")
    private String mimeType;

    public LlmFacadeResponsesContent() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    /** 获取文件地址 */
    public String getFileUrl() { return fileUrl; }
    /** 设置文件地址 */
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    /** 获取文件数据 */
    public String getFileData() { return fileData; }
    /** 设置文件数据 */
    public void setFileData(String fileData) { this.fileData = fileData; }
    /** 获取文件名称 */
    public String getFilename() { return filename; }
    /** 设置文件名称 */
    public void setFilename(String filename) { this.filename = filename; }
    /** 获取音频地址 */
    public String getAudioUrl() { return audioUrl; }
    /** 设置音频地址 */
    public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
    /** 获取视频地址 */
    public String getVideoUrl() { return videoUrl; }
    /** 设置视频地址 */
    public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
    /** 获取内容数据 */
    public String getData() { return data; }
    /** 设置内容数据 */
    public void setData(String data) { this.data = data; }
    /** 获取音视频格式 */
    public String getFormat() { return format; }
    /** 设置音视频格式 */
    public void setFormat(String format) { this.format = format; }
    /** 获取视频帧率 */
    public Double getFps() { return fps; }
    /** 设置视频帧率 */
    public void setFps(Double fps) { this.fps = fps; }
    /** 获取拒绝响应文本 */
    public String getRefusal() { return refusal; }
    /** 设置拒绝响应文本 */
    public void setRefusal(String refusal) { this.refusal = refusal; }
    /** 获取内容资源标识 */
    public String getUri() { return uri; }
    /** 设置内容资源标识 */
    public void setUri(String uri) { this.uri = uri; }
    /** 获取内容媒体类型 */
    public String getMimeType() { return mimeType; }
    /** 设置内容媒体类型 */
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
