package com.ai.agent.starter.controller.vo.doubao;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * @Description: 豆包平台对话请求 VO
 *               豆包（火山方舟）使用 endpoint_id（模型接入点 ID）而非 model_code 来标识模型
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.starter.controller.vo.doubao
 * @ClassName: DoubaoRequest
 * @Author: HUANGcong
 * @Date: Created in 2026/6/4
 * @Version: 1.0
 */
@Data
public class DoubaoRequest {

    /**
     * API Key，可选；为空时从 Nacos ai-agent-doubao.json 的 chat 配置兖底
     */
    private String apiKey;

    /**
     * API 请求地址，可选；为空时从 Nacos 兖底
     */
    private String endpoint;

    /**
     * 豆包模型接入点 ID，可选；为空时从 Nacos 兖底。对应火山方舟控制台的 endpoint_id，如 ep-xxxxxxxx
     */
    private String endpointId;

    /**
     * 对话消息列表，按时间顺序，首条可为 system 角色
     */
    @NotEmpty(message = "messages 不能为空")
    @Valid
    private List<DoubaoMessageVO> messages;

    /**
     * 温度参数，控制随机性，null 时使用平台默认值，范围 [0, 1]
     */
    private Double temperature;

    /**
     * 单次回复最大 token 数，null 时使用平台默认值
     */
    private Integer maxTokens;
}

