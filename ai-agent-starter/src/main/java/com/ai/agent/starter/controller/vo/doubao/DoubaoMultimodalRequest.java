package com.ai.agent.starter.controller.vo.doubao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * @Description: 豆包多模态对话请求 VO（Responses API）
 *               对应火山方舟 /v3/responses 协议，支持图片+文本输入
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.doubao
 * @ClassName: DoubaoMultimodalRequest
 * @Author: HUANGcong
 * @Date: Created in 2026/6/8
 * @Version: 1.0
 */
@Data
public class DoubaoMultimodalRequest {

    /**
     * API Key
     */
    @NotBlank(message = "apiKey 不能为空")
    private String apiKey;

    /**
     * API 请求地址，如 https://ark.cn-beijing.volces.com/api/v3/responses
     */
    @NotBlank(message = "endpoint 不能为空")
    private String endpoint;

    /**
     * 豆包模型接入点 ID，如 ep-xxxxxxxx
     */
    @NotBlank(message = "model 不能为空")
    private String model;

    /**
     * 多模态消息列表
     */
    @NotEmpty(message = "input 不能为空")
    @Valid
    private List<DoubaoMultimodalMessageVO> input;
}

