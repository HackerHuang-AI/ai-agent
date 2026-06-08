package com.ai.agent.starter.controller.vo.doubao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * @Description: 豆包多模态消息 VO（Responses API）
 *               单条消息，包含 role 和多个内容块
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.doubao
 * @ClassName: DoubaoMultimodalMessageVO
 * @Author: HUANGcong
 * @Date: Created in 2026/6/8
 * @Version: 1.0
 */
@Data
public class DoubaoMultimodalMessageVO {

    /**
     * 消息角色：user / assistant
     */
    @NotBlank(message = "role 不能为空")
    private String role;

    /**
     * 内容块列表，支持 input_text 和 input_image 混合
     */
    @NotEmpty(message = "content 不能为空")
    @Valid
    private List<DoubaoMultimodalContentVO> content;
}

