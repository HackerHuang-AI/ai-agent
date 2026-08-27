# ai-agent 项目定位

## 一、平台整体模块划分

| 模块 | 平台职责 | 当前建设状态 |
| --- | --- | --- |
| `ai-base` | 用户、租户、组织、权限与菜单基础域 | 数据模型、迁移脚本、实体与 Mapper 已具备；业务服务和 SSO 待建设 |
| `ai-gateway` | 统一流量入口、认证、路由与追踪 | TraceId、访问日志、JWT 过滤器已实现；路由和 SSO 治理待完善 |
| `ai-agent` | 多 LLM 平台协议适配 | 多平台文本、流式、多模态和 Function Calling 已具备 |
| `ai-mcp` | 内置工具与外部 MCP Server 接入 | 工具路由、MCP Client、SSE/STDIO 接入已具备 |
| `ai-orchestration` | Agent 任务编排与 ReAct 循环 | DTO、Facade 和技术底座已具备；核心编排未实现 |
| `ai-memory` | 会话上下文与长期记忆 | DTO、Facade 和技术底座已具备；存储与检索未实现 |
| `ai-knowledge` | 知识库与 RAG | 知识库、文档、解析、向量化、Milvus/MinIO 接入已具备 |
| `ai-eval` | 模型与 Agent 效果评估 | DTO、Facade 和技术底座已具备；评测执行未实现 |
| `ai-experiment` | 实验分流与版本对比 | DTO、Facade 和技术底座已具备；分流与分析未实现 |
| `ai-analysis` | 当前代码评审/静态分析，后续平台运行分析 | 多代码平台适配、PR 分析及多阶段部署编排已具备 |

## 二、项目背景与定位

不同模型平台在鉴权、请求结构、流式协议、多模态和工具调用上存在差异。`ai-agent` 将这些差异封装为统一模型调用契约，是编排、知识库和评测模块调用模型的唯一协议适配层。

本模块不决定任务流程，不执行工具，不管理会话或知识内容；复杂任务流程由 `ai-orchestration` 负责。

## 三、本模块功能定位

- 按平台路由 OpenAI、DeepSeek、通义、Gemini、Claude 等模型调用；
- 统一 `chat`、`chatStream`、`multimodalChat` 和 Function Calling DTO；
- 处理模型凭证、请求构造、响应解析、流式 SSE 和重试；
- 对内通过 Dubbo 暴露标准 LLM Facade。

## 四、当前建设完整度

- 已完成：多平台 Service 实现、同步/流式调用、多模态适配、工具调用请求与响应转换、Nacos 配置、HTTP 重试、Dubbo Facade。
- 待完成：与 `ai-base` 的租户级模型凭证管理、调用日志的真实持久化及管理台配置闭环。

## 五、主要技术栈

| 功能 | 关键技术栈 |
| --- | --- |
| 多模型协议适配 | OkHttp、Jackson、模型平台 API |
| 流式与工具调用 | SSE、Function Calling、Dubbo Triple Streaming |
| 配置与容错 | Nacos、线程池、指数退避重试 |

