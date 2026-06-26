package com.ai.agent.starter.controller.vo.doubao;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Description: 豆包多模态消息内容单元
 *               Responses API 的 content 为数组，每个元素为一个内容块：
 *               - type=input_text  → text 字段
 *               - type=input_image → image_url 字段
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.doubao
 * @ClassName: DoubaoMultimodalContentVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/8
 * @Version: 1.0
 */
@Data
public class DoubaoMultimodalContentVO {

    /**
     * 内容类型：input_text / input_image
     */
    @NotBlank(message = "content type 不能为空")
    private String type;

    /**
     * 文本内容，type=input_text 时必填
     */
    private String text;

    /**
     * 图片 URL，type=input_image 时必填
     */
    @JsonProperty("image_url")
    private String imageUrl;
}

