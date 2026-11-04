package com.ai.agent.application.model.llm;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Responses API 输入内容块。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LlmResponsesContent {

    /** input_text / input_image */
    private String type;

    /** 文本内容，type=input_text 时必填 */
    private String text;

    /** 图片 URL 或 data URL，type=input_image 时必填 */
    @JsonProperty("image_url")
    @JsonAlias("imageUrl")
    private String imageUrl;

    /** 已上传文件 ID，type=input_image 时可替代 imageUrl */
    @JsonProperty("file_id")
    @JsonAlias("fileId")
    private String fileId;

    /** 图片精度：low / high / auto */
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
}
