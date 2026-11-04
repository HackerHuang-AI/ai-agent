package com.ai.agent.application.model.llm;

import com.ai.agent.application.enums.ContentTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息内容单元，对应 Chat 消息内容块或 Gemini 原生 Interactions 步骤内容；平台及协议标注以当前代码使用范围为准。
 *               type=TEXT  → value 为纯文本字符串
 *               type=IMAGE → value 为图片 URL 或 "data:image/jpeg;base64,<base64>"
 *               type=FILE  → value 为 "data:<mimeType>;base64,<base64>"，detail 为文件名
 *               type=VIDEO → value 为视频 URL 或 "data:video/mp4;base64,<base64>"
 * @author HUANGcong
 * @since 2026-06-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageContent {

    /** 内容类型 */
    private ContentTypeEnum type;

    /**
     * 内容值：
     * TEXT  → 文本字符串
     * IMAGE → URL 或 data URI（data:image/jpeg;base64,xxx）
     * FILE  → data URI（data:application/pdf;base64,xxx）
     * VIDEO → URL 或 data URI（data:video/mp4;base64,xxx）
     */
    private String value;

    /**
     * 附加说明（可选）：
     * IMAGE → 图片细节级别 "low" / "high" / "auto"（OpenAI detail 参数）
     * FILE  → 文件原始名称，用于日志和平台展示
     */
    private String detail;

    /** 文本内容 */
    private String text;

    /** 图片地址及细节级别 */
    private UrlSource imageUrl;

    /** OpenAI / GLM / Gemini / Doubao / Qwen Chat 输入音频来源，各子字段范围见 AudioSource。 */
    private AudioSource inputAudio;

    /** Qwen / Doubao / GLM Chat 视频来源，各子字段范围见 VideoSource。 */
    private VideoSource videoUrl;

    /** OpenAI / Doubao / GLM Chat 文件来源，各子字段范围见 FileSource。 */
    private FileSource file;

    /** GLM Chat 专用：历史 file_url 内容块的文件地址。 */
    private UrlSource fileUrl;

    /** Anthropic Messages 专用：image / document 内容块的 source。 */
    private ContentSource source;

    /** Qwen Chat 专用：内容块顶层视频帧率；Doubao Chat 使用 videoUrl.fps。 */
    private Double fps;

    /** Gemini 原生 Interactions 专用：多模态资源 URI，Chat 不使用。 */
    private String uri;

    /** Gemini 原生 Interactions 专用：多模态裸 Base64 数据，Chat 不使用。 */
    private String data;

    /** Gemini 原生 Interactions 专用：多模态 MIME 类型，Chat 不使用。 */
    private String mimeType;

    /** 兼容原有内容类型、内容值和附加说明的三参构造 */
    public MessageContent(ContentTypeEnum type, String value, String detail) {
        this.type = type;
        this.value = value;
        this.detail = detail;
    }

    /** 地址来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UrlSource {
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
    public static class AudioSource {
        /** OpenAI / GLM / Gemini / Doubao Chat：裸 Base64；Qwen Chat：URL 或 Base64 data URI。 */
        private String data;
        /** OpenAI / GLM / Gemini Chat：wav / mp3；Qwen Chat 必填；Doubao Chat 使用 data/url 时必填。 */
        private String format;
        /** Doubao Chat 专用：音频 URL，与 data/fileId 互斥。 */
        private String url;
        /** Doubao Chat 专用：已上传音频文件标识，与 data/url 互斥。 */
        private String fileId;
    }

    /** 视频来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class VideoSource {
        /** Qwen / Doubao Chat：URL 或 Base64 data URI；GLM Chat：仅 URL。 */
        private String url;
        /** Doubao Chat 专用：已上传视频文件标识，与 url 互斥。 */
        private String fileId;
        /** Doubao Chat 专用：video_url 节点内视频帧率；Qwen Chat 使用内容块顶层 fps。 */
        private Double fps;
    }

    /** 文件来源 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FileSource {
        /** OpenAI / Doubao / GLM Chat：已上传文件标识，与 fileData/fileUrl 互斥。 */
        private String fileId;
        /** OpenAI / GLM Chat 专用：文件数据；OpenAI 要求 PDF Base64 data URI，与 fileId/fileUrl 互斥。 */
        private String fileData;
        /** GLM Chat 专用：文件 URL，与 fileId/fileData 互斥。 */
        private String fileUrl;
        /** OpenAI / GLM Chat 专用：使用 fileData 时必填的文件名称。 */
        private String filename;
    }

    /** Anthropic Messages 专属 image / document 内容块来源，全部字段仅用于该协议。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ContentSource {
        /** Anthropic Messages：base64 / url；document 还可用 file / text。 */
        private String type;
        /** Anthropic Messages：base64 来源的 MIME 类型；document 的 text 来源要求 text/plain。 */
        private String mediaType;
        /** Anthropic Messages：base64 来源为裸 Base64，document 的 text 来源为纯文本，与 url/fileId 互斥。 */
        private String data;
        /** Anthropic Messages：url 来源的资源 URL，与 data/fileId 互斥。 */
        private String url;
        /** Anthropic Messages 专用：仅 document 的 file 来源使用的已上传文件标识，与 data/url 互斥。 */
        private String fileId;
    }

    /** 便捷工厂方法：纯文本 */
    public static MessageContent ofText(String text) {
        return new MessageContent(ContentTypeEnum.TEXT, text, null);
    }

    /** 便捷工厂方法：图片 URL */
    public static MessageContent ofImage(String urlOrBase64) {
        return new MessageContent(ContentTypeEnum.IMAGE, urlOrBase64, null);
    }

    /** 便捷工厂方法：文件（Base64 data URI + 文件名） */
    public static MessageContent ofFile(String dataUri, String fileName) {
        return new MessageContent(ContentTypeEnum.FILE, dataUri, fileName);
    }

    /** 便捷工厂方法：视频 URL 或 Base64 */
    public static MessageContent ofVideo(String urlOrBase64) {
        return new MessageContent(ContentTypeEnum.VIDEO, urlOrBase64, null);
    }
}

