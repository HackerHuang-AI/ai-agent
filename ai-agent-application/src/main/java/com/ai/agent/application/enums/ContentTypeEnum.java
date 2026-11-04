package com.ai.agent.application.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * 消息内容类型枚举
 *               对应各平台 API 中 content 数组的 type 字段：
 *               - TEXT   → 所有平台支持
 *               - IMAGE  → 支持 vision 的模型（llm_model.support_vision=1）
 *               - FILE   → 文档类文件（PDF/Word 等），部分平台原生支持，其余走文本提取
 *               - VIDEO  → 视频，Gemini / Qwen-VL / 智谱 GLM-4V 原生支持
 * @author HUANGcong
 * @since 2026-06-02
 */
public enum ContentTypeEnum {

    /** 纯文本 */
    TEXT,

    /** 图片，内容为 Base64 或 URL */
    IMAGE,

    /** 文档文件，内容为 Base64（PDF/Word/txt 等） */
    FILE,

    /** 视频，内容为 Base64 或 URL（仅支持 vision 且 support_video 的模型） */
    VIDEO,

    /** 图片地址内容 */
    IMAGE_URL,

    /** 输入音频内容 */
    INPUT_AUDIO,

    /** 视频地址内容 */
    VIDEO_URL,

    /** 文件地址内容 */
    FILE_URL,

    /** 文档内容 */
    DOCUMENT,

    /** 音频内容 */
    AUDIO,

    /** 输入文本内容 */
    INPUT_TEXT,

    /** 输入图片内容 */
    INPUT_IMAGE,

    /** 输入文件内容 */
    INPUT_FILE,

    /** 输入视频内容 */
    INPUT_VIDEO,

    /** 输出文本内容 */
    OUTPUT_TEXT,

    /** 推理文本内容 */
    REASONING_TEXT,

    /** 摘要文本内容 */
    SUMMARY_TEXT,

    /** 拒绝响应内容 */
    REFUSAL,

    /** 思考内容 */
    THINKING,

    /** 脱敏思考内容 */
    REDACTED_THINKING,

    /** 工具调用内容 */
    TOOL_USE,

    /** 工具结果内容 */
    TOOL_RESULT;

    /** 获取协议使用的小写类型，不改变枚举的 JSON 序列化值 */
    public String getWireType() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 按大小写兼容方式解析内容类型 */
    @JsonCreator
    public static ContentTypeEnum fromString(String type) {
        return valueOf(type.toUpperCase(Locale.ROOT));
    }
}

