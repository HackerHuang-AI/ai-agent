-- ============================================================
-- LLM 平台表
-- 用途：管理接入的 AI 模型平台，如 OpenAI、Deepseek、智谱等
-- ============================================================

CREATE TABLE IF NOT EXISTS `llm_platform`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `code`             VARCHAR(32)      NOT NULL                   COMMENT '平台唯一标识，英文大写，如 OPENAI / DEEPSEEK / ANTHROPIC / QWEN / DOUBAO / MINIMAX / ZHIPU / GOOGLE / MOONSHOT',
    `name`             VARCHAR(64)      NOT NULL                   COMMENT '平台展示名称，如 OpenAI、智谱、豆包',
    `icon_url`         VARCHAR(256)                                COMMENT '平台图标 URL',
    `sort_order`       INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '列表展示排序，数值越小越靠前',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LLM 平台表，管理接入的 AI 模型平台';


-- ============================================================
-- LLM 模型表
-- 用途：管理各平台下的具体模型，记录能力标签、上下文窗口、RPM 等元数据
-- ============================================================

CREATE TABLE IF NOT EXISTS `llm_model`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `platform_code`    VARCHAR(32)      NOT NULL                   COMMENT '所属平台标识，关联 llm_platform.code',
    `model_code`       VARCHAR(64)      NOT NULL                   COMMENT '模型唯一标识，如 gpt-4.1、deepseek-chat、glm-5，供调用方引用',
    `model_name`       VARCHAR(64)      NOT NULL                   COMMENT '模型展示名称',
    `api_endpoint`     VARCHAR(256)     NOT NULL                   COMMENT '该平台的 API 请求地址',

    -- 模型规格
    `context_window`   INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '上下文窗口大小，单位 token，0=未知',
    `rpm_limit`        INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '每分钟最大请求次数（RPM），0=不限',

    -- 能力标签（独立字段，查询性能优于 JSON，新增能力加列代价低）
    -- support_text: 所有模型默认支持，此处显式记录便于前端统一展示
    `support_text`     TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否支持文本生成：1=是 0=否',
    `support_vision`   TINYINT(1)       NOT NULL DEFAULT 0         COMMENT '是否支持图像理解：1=是 0=否',
    `support_json`     TINYINT(1)       NOT NULL DEFAULT 0         COMMENT '是否支持 JSON 格式输出：1=是 0=否',
    `support_tool`     TINYINT(1)       NOT NULL DEFAULT 0         COMMENT '是否支持 Function Call / 调用技能：1=是 0=否',
    `support_stream`   TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否支持流式输出：1=是 0=否',

    `is_featured`      TINYINT(1)       NOT NULL DEFAULT 0         COMMENT '是否为精选模型：1=是 0=否',
    `sort_order`       INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '同平台内展示排序，数值越小越靠前',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_code`    (`model_code`),
    KEY `idx_platform_code`       (`platform_code`, `valid`),
    KEY `idx_is_featured`         (`is_featured`, `valid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LLM 模型表，管理各平台下的具体模型及能力标签';


-- ============================================================
-- LLM API Key 表
-- 用途：管理各平台的 API Key，与模型表隔离，支持多 Key 轮换
-- 注意：api_key 字段应用层加密存储，生产环境建议迁移至 Vault 管理
-- ============================================================

CREATE TABLE IF NOT EXISTS `llm_api_key`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `platform_code`    VARCHAR(32)      NOT NULL                   COMMENT '所属平台标识，关联 llm_platform.code',
    `api_key`          VARCHAR(512)     NOT NULL                   COMMENT 'API Key，应用层 AES 加密后存储，禁止明文',
    `alias`            VARCHAR(64)                                 COMMENT 'Key 备注，如 主Key、备用Key、测试Key',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    KEY `idx_platform_code` (`platform_code`, `valid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LLM API Key 表，与模型表隔离存储，支持多 Key 轮换，api_key 应用层加密';


-- ============================================================
-- Agent 主表
-- 用途：管理 Agent 的基础信息、绑定模型、会话参数、召回参数
-- agent_type 枚举：
--   DEEP_AGENT    强化 ReAct 与工具调用能力，适用于复杂任务执行
--   CHAT          通用型对话 Agent，可自定义 prompt，关联知识库/工具/工作流
--   FAQ           FAQ 型对话 Agent，关联 FAQ 型知识，可控性高
--   WORKFLOW      工作流型对话 Agent，100% 触发唯一工作流
-- ============================================================

CREATE TABLE IF NOT EXISTS `agent`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    -- 基础信息
    `agent_code`       VARCHAR(64)      NOT NULL                   COMMENT 'Agent 唯一标识，英文，供服务调用方引用',
    `name`             VARCHAR(20)      NOT NULL                   COMMENT 'Agent 名称，最多 20 字',
    `description`      VARCHAR(500)                                COMMENT 'Agent 描述，最多 500 字',
    `avatar_url`       VARCHAR(256)                                COMMENT 'Agent 头像 URL',
    `agent_type`       VARCHAR(16)      NOT NULL                   COMMENT 'Agent 类型：DEEP_AGENT / CHAT / FAQ / WORKFLOW',
    `application_scene` VARCHAR(64)                                COMMENT '应用场景标识',
    `data_security_level` VARCHAR(16)                              COMMENT '数据密级，如 L1 / L2 / L3 / L4',
    `scope`            VARCHAR(256)                                COMMENT '适用范围描述，不少于 4 字',

    -- 绑定模型
    `model_code`       VARCHAR(64)      NOT NULL                   COMMENT '绑定的 LLM 模型标识，关联 llm_model.model_code',
    `system_prompt`    TEXT                                        COMMENT '系统提示词（System Prompt）',

    -- 会话设置
    `max_session_turns` INT UNSIGNED    NOT NULL DEFAULT 5         COMMENT '最大记住对话轮次，超出后丢弃最早的消息',
    `max_reply_tokens`  INT UNSIGNED    NOT NULL DEFAULT 4096      COMMENT '单次回复最大 token 数',

    -- 召回设置（recall_enabled=0 时以下字段忽略）
    `recall_enabled`        TINYINT(1)  NOT NULL DEFAULT 0         COMMENT '是否开启召回：1=开启 0=关闭',
    `recall_mode`           VARCHAR(16)                            COMMENT '召回模式：HYBRID=混合召回 VECTOR=向量召回 KEYWORD=关键词召回',
    `rerank_enabled`        TINYINT(1)  NOT NULL DEFAULT 0         COMMENT '是否开启 Rerank 重排：1=开启 0=关闭',
    `max_doc_recall`        INT UNSIGNED NOT NULL DEFAULT 3        COMMENT '文档切片最大召回数量',
    `max_faq_recall`        INT UNSIGNED NOT NULL DEFAULT 8        COMMENT 'FAQ 切片最大召回数量',
    `max_skill_recall`      INT UNSIGNED NOT NULL DEFAULT 3        COMMENT '技能最大召回数量（不含固定召回）',
    `max_workflow_recall`   INT UNSIGNED NOT NULL DEFAULT 3        COMMENT '工作流最大召回数量',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_code`  (`agent_code`),
    KEY `idx_model_code`        (`model_code`, `valid`),
    KEY `idx_agent_type`        (`agent_type`, `valid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 主表，管理 Agent 基础信息、绑定模型、会话与召回参数';


-- ============================================================
-- Agent 关联知识库表（多对多）
-- ============================================================

CREATE TABLE IF NOT EXISTS `agent_knowledge`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `agent_id`         BIGINT UNSIGNED  NOT NULL                   COMMENT '关联 agent.id',
    `knowledge_id`     BIGINT UNSIGNED  NOT NULL                   COMMENT '关联知识库 ID',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_knowledge` (`agent_id`, `knowledge_id`),
    KEY `idx_agent_id`              (`agent_id`, `valid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 关联知识库关系表';


-- ============================================================
-- Agent 关联工具表（多对多）
-- ============================================================

CREATE TABLE IF NOT EXISTS `agent_tool`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `agent_id`         BIGINT UNSIGNED  NOT NULL                   COMMENT '关联 agent.id',
    `tool_id`          BIGINT UNSIGNED  NOT NULL                   COMMENT '关联工具 ID',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_tool` (`agent_id`, `tool_id`),
    KEY `idx_agent_id`         (`agent_id`, `valid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 关联工具关系表';


-- ============================================================
-- Agent 关联工作流表（多对多）
-- ============================================================

CREATE TABLE IF NOT EXISTS `agent_workflow`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `agent_id`         BIGINT UNSIGNED  NOT NULL                   COMMENT '关联 agent.id',
    `workflow_id`      BIGINT UNSIGNED  NOT NULL                   COMMENT '关联工作流 ID',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_workflow` (`agent_id`, `workflow_id`),
    KEY `idx_agent_id`             (`agent_id`, `valid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 关联工作流关系表';


-- ============================================================
-- Agent 关联 MCP 服务表（多对多）
-- ============================================================

CREATE TABLE IF NOT EXISTS `agent_mcp`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `agent_id`         BIGINT UNSIGNED  NOT NULL                   COMMENT '关联 agent.id',
    `mcp_id`           BIGINT UNSIGNED  NOT NULL                   COMMENT '关联 MCP 服务 ID',

    -- 基础字段
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_mcp` (`agent_id`, `mcp_id`),
    KEY `idx_agent_id`        (`agent_id`, `valid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 关联 MCP 服务关系表';


-- ============================================================
-- LLM 调用记录表
-- 用途：记录每次 LLM 调用的 token 消耗、耗时、状态，用于计费审计和问题排查
-- 说明：此表为追加写入，不做更新，量大后按月分表或归档
-- ============================================================

CREATE TABLE IF NOT EXISTS `llm_call_log`
(
    `id`               BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT    COMMENT '主键ID',

    `trace_id`         VARCHAR(64)                                 COMMENT '全链路追踪 ID，接入 Micrometer/OpenTelemetry 后自动填充',
    `agent_id`         BIGINT UNSIGNED                             COMMENT '发起调用的 Agent ID，关联 agent.id；NULL=非 Agent 直接调用',
    `session_id`       VARCHAR(64)                                 COMMENT '会话 ID，用于关联同一会话的多轮调用',
    `model_code`       VARCHAR(64)      NOT NULL                   COMMENT '实际使用的模型标识，关联 llm_model.model_code',

    -- 消耗统计
    `input_tokens`     INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '本次调用消耗的输入 token 数',
    `output_tokens`    INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '本次调用消耗的输出 token 数',
    `latency_ms`       INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '调用总耗时，单位毫秒',

    -- 调用结果
    -- status 枚举：SUCCESS=成功 FAILED=业务失败 TIMEOUT=超时 RATE_LIMIT=限流
    `status`           VARCHAR(16)      NOT NULL                   COMMENT '调用结果：SUCCESS / FAILED / TIMEOUT / RATE_LIMIT',
    `finish_reason`    VARCHAR(32)                                 COMMENT '模型返回的结束原因：stop / length / tool_calls / content_filter',
    `error_msg`        VARCHAR(512)                                COMMENT '失败时的错误信息',

    -- 基础字段（此表只追加写入，update_user_id / update_time / version 保留但无实际意义）
    `create_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '创建人用户ID',
    `create_time`      DATETIME         NOT NULL DEFAULT NOW()     COMMENT '创建时间',
    `update_user_id`   BIGINT UNSIGNED  NOT NULL                   COMMENT '最后更新人用户ID',
    `update_time`      DATETIME         NOT NULL DEFAULT NOW() ON UPDATE NOW() COMMENT '最后更新时间',
    `valid`            TINYINT(1)       NOT NULL DEFAULT 1         COMMENT '是否有效：1=启用 0=禁用',
    `version`          INT UNSIGNED     NOT NULL DEFAULT 0         COMMENT '版本号，乐观锁 + 缓存一致性，每次业务更新由代码层 +1',

    PRIMARY KEY (`id`),
    KEY `idx_agent_id`    (`agent_id`, `create_time`),
    KEY `idx_session_id`  (`session_id`),
    KEY `idx_model_code`  (`model_code`, `create_time`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LLM 调用记录表，追加写入，记录 token 消耗、耗时与状态，用于计费审计和问题排查';


-- ============================================================
-- 初始化数据
-- ============================================================

-- 平台数据
INSERT INTO `llm_platform` (`code`, `name`, `sort_order`, `create_user_id`, `update_user_id`) VALUES
('OPENAI',    'OpenAI',    1, 1, 1),
('DEEPSEEK',  'Deepseek',  2, 1, 1),
('QWEN',      'Qwen',      3, 1, 1),
('DOUBAO',    '豆包',       4, 1, 1),
('MINIMAX',   'Minimax',   5, 1, 1),
('ANTHROPIC', 'Anthropic', 6, 1, 1),
('ZHIPU',     '智谱',       7, 1, 1),
('GOOGLE',    'Google',    8, 1, 1),
('MOONSHOT',  'Moonshot',  9, 1, 1);


-- 模型数据（OpenAI）
-- api_endpoint 开发阶段占位，生产通过配置或 Vault 覆盖
INSERT INTO `llm_model`
    (`platform_code`, `model_code`, `model_name`, `api_endpoint`,
     `context_window`, `rpm_limit`,
     `support_text`, `support_vision`, `support_json`, `support_tool`, `support_stream`,
     `is_featured`, `sort_order`, `create_user_id`, `update_user_id`)
VALUES
('OPENAI', 'gpt-4o-mini',       'GPT-4o Mini',       'https://api.openai.com/v1/chat/completions', 128000, 200,  1, 1, 1, 1, 1, 0, 1, 1, 1),
('OPENAI', 'gpt-4.1',           'GPT-4.1',            'https://api.openai.com/v1/chat/completions', 1048576, 200, 1, 1, 1, 1, 1, 1, 2, 1, 1),
('OPENAI', 'gpt-4.1-mini',      'GPT-4.1 Mini',       'https://api.openai.com/v1/chat/completions', 1048576, 500, 1, 1, 1, 1, 1, 0, 3, 1, 1),
('OPENAI', 'gpt-4.1-nano',      'GPT-4.1 Nano',       'https://api.openai.com/v1/chat/completions', 1048576, 200, 1, 1, 1, 1, 1, 0, 4, 1, 1),
('OPENAI', 'gpt-5',             'GPT-5',              'https://api.openai.com/v1/chat/completions', 200000, 0,   1, 1, 1, 1, 1, 1, 5, 1, 1),
('OPENAI', 'gpt-5-mini',        'GPT-5 Mini',         'https://api.openai.com/v1/chat/completions', 200000, 0,   1, 1, 1, 1, 1, 0, 6, 1, 1),
-- 智谱 GLM 系列（OpenAI 兼容协议）
('ZHIPU',  'glm-4.6',           'GLM-4.6',            'https://open.bigmodel.cn/api/paas/v4/chat/completions', 0, 30,  1, 0, 0, 1, 1, 0, 1, 1, 1),
('ZHIPU',  'glm-4.6v',          'GLM-4.6V',           'https://open.bigmodel.cn/api/paas/v4/chat/completions', 0, 30,  1, 1, 0, 1, 1, 0, 2, 1, 1),
('ZHIPU',  'glm-4.7',           'GLM-4.7',            'https://open.bigmodel.cn/api/paas/v4/chat/completions', 0, 30,  1, 0, 0, 1, 1, 0, 3, 1, 1),
('ZHIPU',  'glm-5',             'GLM-5',              'https://open.bigmodel.cn/api/paas/v4/chat/completions', 0, 5,   1, 0, 0, 1, 1, 0, 4, 1, 1),
('ZHIPU',  'glm-5.1',           'GLM-5.1',            'https://open.bigmodel.cn/api/paas/v4/chat/completions', 0, 50,  1, 0, 0, 1, 1, 0, 5, 1, 1),
('ZHIPU',  'glm-5-turbo',       'GLM-5-Turbo',        'https://open.bigmodel.cn/api/paas/v4/chat/completions', 0, 50,  1, 0, 0, 1, 1, 0, 6, 1, 1);

