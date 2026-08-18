# ai-agent 项目定位

## 项目背景

不同 LLM 平台在请求协议、鉴权、流式响应、多模态和 Function Calling 方面存在差异。平台业务若直接依赖这些差异，会导致接入、切换和扩展成本持续上升。

## 项目定位

`ai-agent` 是平台的 LLM 协议网关，统一封装不同模型平台的调用差异，对内提供标准化的文本、多模态、流式和工具调用能力。

## 主要功能

- 路由调用 OpenAI、DeepSeek、通义、Gemini、Claude 等模型平台；
- 统一 `chat`、`chatStream` 与 `multimodalChat` 调用契约；
- 统一 Function Calling 工具定义、工具调用结果和历史消息协议；
- 管理模型调用参数、凭证读取、HTTP 重试和调用日志。

## 主要功能对应技术栈

| 功能 | 关键技术栈 |
| --- | --- |
| 多模型协议适配 | OkHttp、Jackson、各模型 OpenAI 兼容或专属 API |
| 统一模型服务接口 | Dubbo Triple、Facade DTO |
| 流式模型调用 | SSE、Dubbo Server Streaming |
| 模型配置与容错 | Nacos 动态配置、指数退避重试、线程池 |

