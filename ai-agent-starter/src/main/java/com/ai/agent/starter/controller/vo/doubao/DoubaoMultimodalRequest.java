package com.ai.agent.starter.controller.vo.doubao;

import jakarta.validation.Valid;
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
     * API Key，可选；为空时从 Nacos ai-agent-doubao.json 的 multimodal 配置兖底
     */
    private String apiKey;

    /**
     * API 请求地址，可选；为空时从 Nacos 兖底
     */
    private String endpoint;

    /**
     * 豆包模型接入点 ID，可选；为空时从 Nacos 兖底
     */
    private String model;

    /**
     * 多模态消息列表
     */
    @NotEmpty(message = "input 不能为空")
    @Valid
    private List<DoubaoMultimodalMessageVO> input;
}

