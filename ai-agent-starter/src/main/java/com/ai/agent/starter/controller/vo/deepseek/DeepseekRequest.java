package com.ai.agent.starter.controller.vo.deepseek;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * @Description: Deepseek 平台对话请求 VO
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.deepseek
 * @ClassName: DeepseekRequest
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Data
public class DeepseekRequest {

    /**
     * API Key
     */
    @NotBlank(message = "apiKey 不能为空")
    private String apiKey;

    /**
     * API 请求地址
     */
    @NotBlank(message = "endpoint 不能为空")
    private String endpoint;

    /**
     * 模型标识：deepseek-chat / deepseek-reasoner
     */
    @NotBlank(message = "modelCode 不能为空")
    private String modelCode;

    /**
     * 对话消息列表，按时间顺序，首条可为 system 角色
     */
    @NotEmpty(message = "messages 不能为空")
    @Valid
    private List<DeepseekMessageVO> messages;

    /**
     * 温度参数，范围 [0, 2]，null 时使用平台默认值
     * 注意：deepseek-reasoner 模型不支持此参数，传入会被自动忽略
     */
    private Double temperature;

    /**
     * 单次回复最大 token 数，null 时使用平台默认值，上限 8192
     */
    private Integer maxTokens;
}

