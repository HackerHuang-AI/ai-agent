package com.ai.agent.starter.controller.vo.doubao;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Description: 豆包平台对话消息 VO
 *               豆包（火山方舟）仅支持纯文本对话，不支持多模态内容，因此只有 role + content 两个字段
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.doubao
 * @ClassName: DoubaoMessageVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Data
public class DoubaoMessageVO {

    /**
     * 消息角色：system / user / assistant
     */
    @NotBlank(message = "role 不能为空")
    private String role;

    /**
     * 消息文本内容
     */
    @NotBlank(message = "content 不能为空")
    private String content;
}

