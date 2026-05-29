package com.ai.agent.application.enums;

import lombok.Getter;

/**
 * @Description: 业务异常码枚举。
 *
 *               编码规则：[系统编码 2位][模块编码 2位][错误序号 3位]
 *               - 系统编码：20 = ai-agent
 *               - 模块编码：01 = 通用/参数
 *               - 错误序号：001 起步
 *
 *               示例：2001001 → ai-agent · 通用 · 参数非法
 *
 * @ProjectName: ai-agent
 * @Package: com.ai.agent.application.enums
 * @ClassName: ErrorCodeEnum
 * @Author: HUANGcong
 * @Date: Created in 2026/5/29
 * @Version: 1.0
 */
@Getter
public enum ErrorCodeEnum {

    // ==================== 通用（2001xxx）====================
    PARAM_ILLEGAL("2001001", "error.2001001", "参数不合法"),
    SYSTEM_ERROR("2001002", "error.2001002", "系统异常，请稍后重试");

    /** 错误码，格式：系统编码(2) + 模块编码(2) + 错误序号(3) */
    private final String code;

    /** i18n 资源文件 key，对应 messages.properties 中的条目 */
    private final String messageKey;

    /** 默认中文描述，i18n 不可用时的兜底 */
    private final String defaultMessage;

    ErrorCodeEnum(String code, String messageKey, String defaultMessage) {
        this.code = code;
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }
}

