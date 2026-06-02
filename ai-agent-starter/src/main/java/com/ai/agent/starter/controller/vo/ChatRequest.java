package com.ai.agent.starter.controller.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * @Description: LLM 对话接口请求 VO
 *               用户只需关心：用哪个模型、发什么内容（文本/图片/文件/视频）
 *               apiKey、endpoint 等关键信息由服务端从 DB 读取，不对外暴露
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo
 * @ClassName: ChatRequest
 * @Author: HUANGcong
 * @Date: Created in 2026/6/2
 * @Version: 1.0
 */
@Data
public class ChatRequest {

    /**
     * 模型标识，对应 llm_model.model_code，如 gpt-4.1、glm-5、deepseek-chat
     */
    @NotBlank(message = "modelCode 不能为空")
    private String modelCode;

    /**
     * 对话消息列表，按时间顺序，首条可为 system 角色
     * 每条消息包含 role + type + value，支持多模态混合
     */
    @NotEmpty(message = "messages 不能为空")
    @Valid
    private List<ChatMessageVO> messages;

    /**
     * 温度参数，控制随机性，null 时使用平台默认值
     * 注意：不同平台取值范围不同，Adapter 层会自动夹紧到平台允许范围
     */
    private Double temperature;

    /**
     * 单次回复最大 token 数，null 时使用平台默认值
     */
    private Integer maxTokens;
}

