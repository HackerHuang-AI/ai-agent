package com.ai.agent.application.model.llm;

import com.ai.agent.application.enums.ContentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: 消息内容单元，对应各平台 messages[].content[] 中的一个元素
 *               type=TEXT  → value 为纯文本字符串
 *               type=IMAGE → value 为图片 URL 或 "data:image/jpeg;base64,<base64>"
 *               type=FILE  → value 为 "data:<mimeType>;base64,<base64>"，detail 为文件名
 *               type=VIDEO → value 为视频 URL 或 "data:video/mp4;base64,<base64>"
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: MessageContent
 * @Author: HUANGcong
 * @Date: Created in 2026/6/2
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageContent {

    /** 内容类型 */
    private ContentType type;

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

    /** 便捷工厂方法：纯文本 */
    public static MessageContent ofText(String text) {
        return new MessageContent(ContentType.TEXT, text, null);
    }

    /** 便捷工厂方法：图片 URL */
    public static MessageContent ofImage(String urlOrBase64) {
        return new MessageContent(ContentType.IMAGE, urlOrBase64, null);
    }

    /** 便捷工厂方法：文件（Base64 data URI + 文件名） */
    public static MessageContent ofFile(String dataUri, String fileName) {
        return new MessageContent(ContentType.FILE, dataUri, fileName);
    }

    /** 便捷工厂方法：视频 URL 或 Base64 */
    public static MessageContent ofVideo(String urlOrBase64) {
        return new MessageContent(ContentType.VIDEO, urlOrBase64, null);
    }
}

