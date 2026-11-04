# ai-agent 项目定位

## 一、平台整体模块划分

| 模块 | 平台职责 |
| --- | --- |
| `ai-base` | 用户、个人空间、企业租户、组织及租户侧角色、菜单、权限基础域（非运营角色） |
| `ai-operations` | 独立运营身份与权限、平台初始化编排与运营配置 |
| `ai-admin-gateway` | 用户租户入口、认证、路由与追踪 |
| `ai-operations-gateway` | 无租户运营入口、认证、路由与追踪 |
| `ai-admin-web` | 用户 AI 工作台兼企业智能管理平台 |
| `ai-operations-web` | 运营控制台 |
| `ai-agent` | 多 LLM 平台协议适配 |
| `ai-mcp` | 内置工具与外部 MCP Server 接入 |
| `ai-orchestration` | Agent 任务编排与 ReAct 循环 |
| `ai-memory` | 会话上下文与长期记忆 |
| `ai-knowledge` | 知识库与 RAG |
| `ai-eval` | 模型、Agent 及其调用质量评测 |
| `ai-experiment` | 实验分流与版本对比 |
| `ai-analysis` | 传统规则扫描与 AI 代码分析 |

## 二、项目背景与定位

不同模型平台在鉴权、请求结构、流式协议、多模态和工具调用上存在差异。`ai-agent` 将这些差异封装为统一模型调用契约，是编排、知识库和评测模块调用模型的唯一协议适配层。

本模块不决定任务流程，不执行工具，不管理会话或知识内容；复杂任务流程由 `ai-orchestration` 负责。本模块也不负责用户 Agent 设计、分享和版本的产品管理，这些属于平台后续能力，而非当前实现。

## 三、本模块功能定位

- 按平台路由 OpenAI、DeepSeek、通义、Gemini、Claude 等模型调用；
- 统一支持文本和图文内容块的 `chat`、`chatStream`、`responses` 与 Function Calling DTO；
- 处理模型凭证、请求构造、响应解析、流式 SSE 和重试；
- 对内通过 Dubbo 暴露标准 LLM Facade。

## 四、LLM 平台能力建设状态

更新日期：2026-11-03。以下“已实现”表示当前代码已具备协议适配，不等同于厂商全量能力、真实账号联调或上线验收；图文和工具调用均受所选模型及端点限制。

| LLM 平台 | 文本同步 / 流式 | 图文对话（Chat IMAGE） | Function Calling | OpenAI 兼容 Responses 同步 / 流式 | 模型列表 |
| --- | --- | --- | --- | --- | --- |
| OpenAI | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| DeepSeek | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| 通义千问（Qwen） | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| 豆包（Doubao） | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| MiniMax | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| Glm（智谱 GLM） | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| Moonshot（Kimi） | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| 百度千帆（Qianfan） | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| 腾讯 TokenHub | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| 小米 MiMo | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| Ollama | **已实现** | **已实现** | **已实现** | **已实现** | **已实现** |
| Anthropic（Claude） | **已实现** | **已实现** | **已实现** | **未实现** | **已实现** |
| Google Gemini | **已实现** | **已实现** | **已实现** | **未实现** | **已实现** |

### 协议说明与能力边界

- **Claude 未实现的是 OpenAI 兼容 Responses，不是模型调用能力**：项目已适配原生 `POST /v1/messages`，包括文本、图文、工具调用与思考块回填。2026-11-03 核对 [Anthropic 官方 SDK API 索引](https://github.com/anthropics/anthropic-sdk-python/blob/main/api.md)，未查到官方直接兼容 `/responses` 的接口；官方文档站本次受区域限制，不能据此断言厂商绝不支持。当前适配器没有实现 `responses/responsesStream`，将 Responses 入参转译到 Messages 属于额外的协议映射建设。
- **Gemini 已有原生 Interactions，但项目未实现适配**：[官方 Interactions 文档](https://ai.google.dev/gemini-api/docs/interactions)说明该接口已于 2026 年 6 月正式可用，支持 `previous_interaction_id`、后台执行与多模态等能力；它不是 OpenAI Responses。项目目前只适配 OpenAI 兼容 Chat，尚未处理 Interactions 的请求、响应及流式事件；[官方 OpenAI 兼容文档](https://ai.google.dev/gemini-api/docs/openai)本次未查到 `/responses` 支持说明。
- **Responses 输入与工具限制**：Qwen、GLM、千帆、Moonshot、MiniMax、MiMo、TokenHub、Ollama 当前仅接受文本类内容块及 `message/reasoning/function_call/function_call_output` 输入项，工具仅支持 `function`；OpenAI、DeepSeek、豆包可透传现有 DTO 表达的图像字段。具体无状态调用与 `tool_choice` 限制见[接入总览](../llm-openapi-overview.md#网关接入状态说明)。
- **多模态与取消边界**：Chat 仅适配 `TEXT/IMAGE`，`FILE/VIDEO` 未实现；完整文件、音频、视频输入及任意协议原生嵌套结构透传未实现。Responses 流式取消已贯通 HTTP SSE / Dubbo Triple 到上游 HTTP，Chat 未实现同样的取消链路。真实账号联调不属于此处的代码实现状态。

| 平台通用能力 | 当前建设状态 | 现状说明 |
| --- | --- | --- |
| 统一模型路由 | **已实现** | `LlmRouter` 路由 13 个适配器；Chat 和模型列表覆盖 13 家，Responses 同步 / 流式覆盖 11 家。 |
| 协议适配与容错 | **已实现** | 已处理 OpenAI 兼容协议及 Anthropic、Gemini、Ollama 等差异，并具备 Nacos 配置、HTTP 重试和流式解析；Gemini 当前走 OpenAI 兼容端点，非原生 `contents/candidates` 协议。 |
| 服务化调用 | **已实现** | 已通过 Dubbo Facade 对内暴露统一模型调用契约，Responses 同时提供 HTTP SSE 与 Dubbo Triple 流式入口。 |
| Gemini 原生 Interactions API 适配 | **未实现** | 厂商已提供接口，项目仍使用 OpenAI 兼容 Chat，未实现 Interactions 同步 / 流式调用。 |
| 文件 / 音频 / 视频输入适配 | **未实现** | 尚未实现完整协议内容块及跨平台校验；不提供 multipart 上传或本地文件预处理。 |
| 租户级凭证管理 | **未实现** | 尚未与 `ai-base` 联动管理模型凭证和授权范围。 |
| 调用记录与管理台闭环 | **未实现** | 调用日志真实持久化、模型配置管理和运营闭环尚未建设。 |

## 五、主要技术栈

| 功能 | 关键技术栈 |
| --- | --- |
| 多模型协议适配 | OkHttp、Jackson、模型平台 API |
| 流式与工具调用 | SSE、Function Calling、Dubbo Triple Streaming |
| 配置与容错 | Nacos、线程池、指数退避重试 |

