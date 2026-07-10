package com.ai.agent.application.model.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description: 多模态输出内容块，对应豆包 Responses API output[i].summary[j] / output[i].content[j]。
 *               type 取值：
 *                 summary_text  - reasoning 节点下的思考摘要文本
 *                 output_text   - message 节点下的正文文本
 *                 image         - 图片（未来扩展）
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.model.llm
 * @ClassName: LlmContentBlock
 * @Author: HUANGcong
 * @Date: Created in 2026/7/10
 * @Version: 1.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmContentBlock {

    /** 内容块类型：summary_text / output_text / image 等 */
    private String type;

    /** 文本内容，type 为文本类时有值 */
    private String text;

    /** 图片/文件 URL，type 为 image 等媒体类时有值，其余为 null */
    private String url;
}

