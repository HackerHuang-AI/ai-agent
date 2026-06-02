package com.ai.agent.application.enums;

/**
 * @Description: 消息内容类型枚举
 *               对应各平台 API 中 content 数组的 type 字段：
 *               - TEXT   → 所有平台支持
 *               - IMAGE  → 支持 vision 的模型（llm_model.support_vision=1）
 *               - FILE   → 文档类文件（PDF/Word 等），部分平台原生支持，其余走文本提取
 *               - VIDEO  → 视频，Gemini / Qwen-VL / 智谱 GLM-4V 原生支持
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.enums
 * @ClassName: ContentType
 * @Author: HUANGcong
 * @Date: Created in 2026/6/2
 * @Version: 1.0
 */
public enum ContentType {

    /** 纯文本 */
    TEXT,

    /** 图片，内容为 Base64 或 URL */
    IMAGE,

    /** 文档文件，内容为 Base64（PDF/Word/txt 等） */
    FILE,

    /** 视频，内容为 Base64 或 URL（仅支持 vision 且 support_video 的模型） */
    VIDEO
}

