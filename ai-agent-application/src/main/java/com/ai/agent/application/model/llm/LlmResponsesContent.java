package com.ai.agent.application.model.llm;

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
public class LlmResponsesContent {

    /** input_text / input_image */
    private String type;

    /** 文本内容，type=input_text 时必填 */
    private String text;

    /** 图片 URL 或 data URL，type=input_image 时必填 */
    @JsonProperty("image_url")
    private String imageUrl;

    /** 已上传文件 ID，type=input_image 时可替代 imageUrl */
    @JsonProperty("file_id")
    private String fileId;

    /** 图片精度：low / high / auto */
    private String detail;
}
