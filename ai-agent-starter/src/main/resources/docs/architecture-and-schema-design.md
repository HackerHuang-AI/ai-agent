# 项目立意与数据库建表思路

> 版本: v1 | 更新时间: 2026-08-10
> 本文档只讨论架构定位与建表原则，不包含可执行 DDL；具体建表 SQL 待方案确认后另行编写。

---

## 一、项目立意：为什么要做这件事

出发点是搭建一个 **Agent 管理平台**：让业务方能在页面上「定义一个 Agent」——绑定模型、挂知识库、挂工具、挂工作流、挂 MCP 服务——而不是每次都写代码硬编码。

这就决定了整个系统天然拆成两类问题：

1. **运行时问题**：一次请求进来，怎么路由到正确的 LLM、怎么执行工具、怎么召回知识、怎么记住上下文——这是「引擎」，要快、要稳。
2. **管理态问题**：Agent 怎么定义、工具从哪来、MCP Server 怎么配、效果好不好——这是「控制台」，要灵活、要可配置、要能追溯。

现有的 9 个模块，本质上是按「谁在什么阶段做什么事」拆分出来的，不是随意起的名字：

| 模块 | 定位 | 现状 |
|---|---|---|
| `ai-gateway` | 流量入口：鉴权、限流、TraceId 透传 | 有 filter 骨架（JWT/日志/TraceId），无业务逻辑 |
| `ai-agent` | LLM 协议网关：屏蔽 13 个平台的协议差异，对外统一 `chat`/`chatStream`/`multimodalChat` | **代码量最完整**，Function Calling 已全平台打通 |
| `ai-mcp` | 工具与 MCP 接入：内置工具注册 + 外部 MCP Server 接入，统一 `ToolRouter` 对外屏蔽来源差异 | 已有真实 MCP Client 实现（基于官方 mcp-sdk），Dubbo 对外暴露 |
| `ai-orchestration` | 编排引擎：ReAct 循环，串联 LLM 调用 + 工具执行 + 记忆读写 | **仅有 DTO/Facade 骨架**（`OrchestrationRequest` 已定义 `mode: DIRECT/REACT`），核心循环未实现 |
| `ai-memory` | 会话记忆：短期上下文、长期记忆存取 | 空壳（只有 `MemoryFacade`/`MemoryMessage` 骨架） |
| `ai-knowledge` | 知识库 RAG：文档解析、分块、向量化、召回 | **代码量第二完整**，已接 Milvus（向量库）+ MinIO（对象存储），已有 `knowledge_base`/`knowledge_document` 表 |
| `ai-eval` | 模型评估：跑分、基准测试 | 空壳 |
| `ai-experiment` | 实验平台：AB 测试、Prompt 版本对比 | 空壳 |
| `ai-analysis` | 用量分析：成本、Token 消耗统计 | 有完整 Docker 多阶段部署编排（MQ/缓存/搜索/持久化），应用代码较少 |

**关键判断**：这个划分本身是对的（职责边界清晰，符合"引擎类"模块独立于"管理台类"模块的直觉），问题不在于要不要拆模块，而在于——**当前只有 3 个模块（`ai-agent`/`ai-mcp`/`ai-knowledge`）有实质进展，其余 6 个还是空壳**。所以"下一步做什么"应该是"把骨架填实"，而不是"再拆新模块"。

---

## 二、当前数据库现状盘点

在讨论"要不要建表"之前，先明确一个容易被忽略的事实：**你已经建过表了，只是分散在两个模块里，且都还没有代码在读写它们**。

### 已存在的表（`ai-agent-starter/resources/sql/init.sql`，库名 `ai_agent`）

```
llm_platform      -- LLM 平台元数据（OpenAI/Deepseek/Anthropic...）
llm_model         -- 平台下的具体模型 + 能力标签（support_tool/support_vision...）
llm_api_key       -- API Key 管理（应用层加密）
agent             -- Agent 主表：类型(DEEP_AGENT/CHAT/FAQ/WORKFLOW)、绑定模型、召回参数
agent_knowledge   -- Agent × 知识库 关联（多对多）
agent_tool        -- Agent × 工具 关联（多对多）
agent_workflow    -- Agent × 工作流 关联（多对多）
agent_mcp         -- Agent × MCP服务 关联（多对多）
llm_call_log      -- 调用日志：token 消耗、耗时、状态（追加写）
```

这份表设计已经和你截图里那个"关联知识库/工具/工作流/MCP服务"配置页完全对应——`agent_tool`、`agent_mcp` 就是为它准备的。

### 已存在的表（`ai-knowledge-starter/resources/sql/init.sql`，库名 `ai_knowledge`）

```
knowledge_base      -- 知识库元数据
knowledge_document  -- 文档元数据（状态机：待处理→解析中→向量化中→完成/失败）
```

### 现存的核心问题：表和代码是脱节的

1. **`agent`/`agent_tool`/`agent_mcp` 这几张表目前没有任何 Mapper/Service 在用**——`ai-agent-starter` 的 `resources/mapper/` 目录是空的，也没接 MyBatis-Plus 依赖（对比 `ai-analysis-starter/pom.xml` 才有 `mysql-connector-j` + `mybatis-plus`）。也就是说，这份 DDL 目前只是"设计稿"，没有落地成可运行的持久层。
2. **`agent_tool`/`agent_mcp` 只存了关联 ID（`tool_id`/`mcp_id`），但没有对应的 `tool` 主表和 `mcp_server` 主表**——现在"工具"是靠 `@Service("toolName")` 硬编码在 `ai-mcp` 代码里，"MCP Server"是靠 Nacos 的 `ai-mcp-config.json`（`McpServerConfig`：name/transport/url/command/args）配置的，两者都不在数据库里，管理台没法对它们做增删改查。
3. **`ai_agent` 库和 `ai_knowledge` 库是两个独立的库**，`agent_knowledge` 表里的 `knowledge_id` 是跨库外键（只能应用层保证一致性，不能建物理外键）——这是分库分表场景下的正常设计，但需要在文档里明确写出来，避免以后有人想当然加 `FOREIGN KEY`。

---

## 三、建表的核心思路（原则，非具体 DDL）

不急着写新 SQL，先定几条原则，后面每张表的取舍都可以照这几条推：

### 原则 1：一张表只为「已经有真实读写方」的功能而建

现在 6 个空壳模块（`ai-memory`/`ai-eval`/`ai-experiment`/`ai-gateway`/`ai-orchestration`/`ai-analysis`）里，不该现在就把表设计齐全——没有 Service 代码去读写的表，只会变成"设计时看起来很美、上线后没人维护、字段和代码逐渐脱节"的僵尸表。

**推论**：`ai-orchestration` 要落地 ReAct 循环了，才需要考虑"要不要给编排过程建执行记录表"；`ai-memory` 要开始存对话历史了，才需要建 `memory_message` 表。现在都还早。

### 原则 2：管理态数据（配置）和运行态数据（日志/记录）要分开建库思考

- **管理态**：`agent`、`llm_platform`、`llm_model`、未来的 `tool`、`mcp_server`、`workflow`——这些是低频写、高频读、需要事务一致性的**配置数据**，适合放在你现有的 `ai_agent` 库（MySQL），走 MyBatis-Plus 标准 CRUD。
- **运行态**：`llm_call_log`、未来的编排执行轨迹、记忆消息——这些是高频写、低频读、允许最终一致、量大后要分表归档的**流水数据**。`llm_call_log` 已经在注释里写明"此表为追加写入，量大后按月分表或归档"，这个思路是对的，后续同类表都应该照此设计，不要和配置表混在一起。

### 原则 3：先补「主表」，再谈关联表的落地

`agent_tool`/`agent_mcp` 关联表已经建好了，但它们引用的 `tool_id`/`mcp_id` 目前无处可查——因为主表不存在。如果要让你截图那个管理页面真正跑起来，**缺口不在关联表，而在两张主表**：

- `tool`：工具元信息表。字段大致对应 `ToolDefinition`（name、description、参数 schema）+ 管理态字段（是否内置/是否启用/所属分类）。
- `mcp_server`：MCP Server 配置表。字段大致对应现在 Nacos 里的 `McpServerConfig`（name、transport、url、command、args、timeoutMs、enabled）+ 管理态字段（创建人、创建时间）。

这两张表建好之后，天然会引出一个后续决策点（不是现在要决定的）：**`mcp_server` 该完全取代 Nacos 配置，还是数据库和 Nacos 共存**？目前 `McpClientManager` 是监听 Nacos 变更来热更新连接的，如果要让页面配置直接生效，要么让"页面保存"这个动作去写 Nacos（数据库只做展示层的镜像），要么把 `McpClientManager` 的配置源从 Nacos 换成"数据库 + 定时轮询/消息通知"。这个决策会牵动现有代码，值得单独讨论，不在本次文档展开。

### 原则 4：字段规范延续现有约定，不要另起一套

观察 `ai_agent`/`ai_knowledge` 两份现有 DDL，已经有一套一致的约定，后续新建表应该延续，而不是每个人按自己喜好设计：

- 统一审计字段：`create_user_id`/`create_time`/`update_user_id`/`update_time`/`valid`/`version`（`ai_agent` 库的写法）
- 逻辑删除统一用 `valid`（1=启用 0=禁用），不用物理删除
- `version` 字段用于乐观锁 + 缓存一致性，业务更新时代码层 +1
- 关联表命名统一为 `{主表}_{关联表}`（如 `agent_tool`），唯一键为 `(主键1, 主键2)`

### 原则 5：跨模块引用一律用「业务标识/ID」，不建物理外键

`agent.model_code` 关联 `llm_model.model_code`、`agent_knowledge.knowledge_id` 关联另一个库的 `knowledge_base.id`——这些都只是逻辑关联，现有设计里也没有加 `FOREIGN KEY` 约束，这个选择是对的（微服务/多库场景下物理外键会成为耦合和运维负担），继续保持即可。

---

## 四、下一步建议的落地顺序（仅针对"工具/MCP管理平台"这一件事）

聚焦到你这轮真正想解决的问题——「工具管理平台 / MCP 管理平台」——建表节奏建议是：

1. 先补 `tool` 主表 + `mcp_server` 主表（管理台要用的最小闭环）
2. 给 `ai-agent-starter` 接入 MyBatis-Plus + 数据源（现在还没接，只有 DDL 没有持久层代码）
3. 决定 `mcp_server` 表和 Nacos `ai-mcp-config.json` 的关系（覆盖 or 共存），这一步会影响 `McpClientManager` 的改造范围
4. 最后才是把 `agent_tool`/`agent_mcp` 这两张已经建好的关联表接上 Service 层，让截图里的管理页面真正可用

这四步都确认后，可以再回来把具体 DDL 写出来。

