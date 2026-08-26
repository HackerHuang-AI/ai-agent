# AI Agent — 多平台 LLM 统一网关

> 基于 Spring Boot 的生产级服务，通过统一 API 接入 13 个主流 LLM 平台，支持 Nacos 热更新、平台级连接池隔离、智能重试和 SSE 流式推送。

---

## 支持平台

| 平台 | 厂商 | 同步对话 | 流式对话 | 图文专用入口 | 工具调用 | 模型列表 |
|------|------|:------:|:------:|:----:|:------:|:------:|
| **豆包** | 字节跳动 | ✅ | ✅ | ✅ | ✅ | ✅ |
| **DeepSeek** | 深度求索 | ✅ | ✅ | — | ✅ | — |
| **OpenAI** | OpenAI | ✅ | ✅ | — | ✅ | — |
| **Anthropic** | Anthropic | ✅ | ✅ | — | ✅ | — |
| **Gemini** | Google | ✅ | ✅ | — | ✅ | — |
| **通义千问** | 阿里巴巴 | ✅ | ✅ | — | ✅ | — |
| **智谱** | 智谱 AI | ✅ | ✅ | — | ✅ | — |
| **Moonshot** | 月之暗面 | ✅ | ✅ | — | ✅ | — |
| **Minimax** | Minimax | ✅ | ✅ | — | ✅ | — |
| **千帆** | 百度 | ✅ | ✅ | — | ✅ | ✅ |
| **Tokenhub** | 内部平台 | ✅ | ✅ | — | ✅ | — |
| **Mimo** | 小米 | ✅ | ✅ | — | ✅ | — |
| **Ollama** | 本地部署 | ✅ | ✅ | ✅ | ✅ | 仅内部¹ |

> 能力表中的“图文专用入口”指 `POST /api/llm/chat/multimodal`：豆包和 Ollama 已实现；其他平台调用该入口会返回空结果。常规 `chat`/`chat/stream` 请求中的 `IMAGE` 内容会由各平台适配器转换为对应的图像块格式；是否可用取决于所选模型和厂商端点是否支持视觉输入。
>
> ¹ `OllamaServiceImpl` 已通过 `/api/tags` 实现模型列表查询，但当前未暴露 HTTP `POST /api/ollama/models` 接口。可用的 HTTP 模型列表接口为 `POST /api/doubao/models` 和 `POST /api/qianfan/models`。

---

## 架构设计

```
┌─────────────────────────────────────────────────────┐
│                     网关层                           │
│            （鉴权 / 限流 / 负载均衡）                │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│               ai-agent-starter                       │
│   LlmChatController（统一路由接口）                  │
│   XxxChatController（各平台独立接口，保留特色能力）   │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│              ai-agent-application                    │
│   LlmRouter     → 按平台名称动态路由                 │
│   XxxServiceImpl → 各平台协议适配                   │
│   AppRetryUtil  → 业务感知重试                      │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│             ai-agent-infrastructure                  │
│   OkHttpConfig    → 各平台独立连接池                 │
│   RetryConfig     → Nacos 重试参数                  │
│   NacosConfigUtil → 热更新配置读取                  │
└─────────────────────────────────────────────────────┘
```

### 模块职责

| 模块 | 职责 |
|------|------|
| `ai-agent-client` | 供 RPC 消费方使用的共享 DTO 和 Facade 接口 |
| `ai-agent-application` | 业务逻辑：路由、适配、重试、异常映射 |
| `ai-agent-infrastructure` | 技术能力：OkHttp 连接池、Nacos、线程池 |
| `ai-agent-starter` | Spring Boot 入口：Controller、全局异常处理 |

---

## 核心设计

### 1. 平台级 OkHttp 连接池隔离
每个平台拥有独立的 `OkHttpClient` 实例，超时时间和连接池大小独立配置，平台间互不影响。配置通过 Nacos 热更新，**无需重启**。

### 2. 动态平台路由
`LlmRouter` 利用 Spring 的 `Map<String, LlmService>` 注入机制，Bean 名称即平台 key。**新增平台无需修改路由代码**，只需新增 `XxxServiceImpl` 并添加 `@Service("xxxServiceImpl")` 注解。

### 3. 两层重试机制

| 层次 | 类 | 适用场景 |
|------|----|---------|
| 同步（chat / multimodal）| `AppRetryUtil.retry()` | 完整 HTTP 调用在 lambda 内重试；指数退避 + 10% 随机抖动 |
| 流式（chatStream）| `AppRetryUtil.retryForStream()` | 仅连接建立阶段重试；一旦开始推 chunk 不重试 |

不可重试错误码（如认证失败、余额不足）通过 Nacos 按平台配置，两层均遵循该配置。

### 4. 流式重试边界设计
重试边界为 `response.isSuccessful()`。连接建立前的网络异常和 HTTP 5xx 触发重试；一旦开始读取 chunk，连接持续保持直到收到 `[DONE]` 或 `[ERROR]`，最终在 `finally` 块释放。

### 5. 平台专属 HTTP 状态码枚举
每个平台都有对应的 `XxxHttpCodeEnum`（如 `DoubaoHttpCodeEnum`、`DeepseekHttpCodeEnum`），业务代码中**没有任何魔法数字**。

---

## API 说明

### 统一接口（通过 `platform` 字段路由）

以下 HTTP 接口均使用 `/ai-agent` 作为 context-path 前缀。

**POST** `/ai-agent/api/llm/chat`
```json
{
  "platform": "doubao",
  "apiKey": "可选，不填时从 Nacos 读取",
  "endpoint": "可选，不填时从 Nacos 读取",
  "modelCode": "ep-20240101-xxxxx",
  "messages": [
    {"role": "user", "type": "TEXT", "value": "你好"}
  ],
  "temperature": 0.7,
  "maxTokens": 2048,
  "tools": [{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object"}}}],
  "toolChoice": "auto",
  "extraParams": {"presence_penalty": 0.3}
}
```

**POST** `/ai-agent/api/llm/chat/stream` — SSE 流式，`Content-Type: text/event-stream`

**POST** `/ai-agent/api/llm/chat/multimodal` — 图文专用入口；当前由豆包和 Ollama 实现。其他平台请通过常规 `chat`/`chat/stream` 传入 `IMAGE` 内容，并选择支持视觉的模型。

`tools` 和 `toolChoice` 使用 OpenAI 兼容的函数调用格式。`extraParams` 会合并到对应平台的请求体，用于传递平台私有参数。`topK` 不适用于 OpenAI、Moonshot 和 DeepSeek；`frequencyPenalty` 不适用于 Anthropic，在 DeepSeek 中已废弃，Moonshot 文档未定义该参数。

### 平台专属接口
每个平台同时保留 `/ai-agent/api/{platform}/chat` 和 `/ai-agent/api/{platform}/chat/stream`。豆包额外提供 `POST /ai-agent/api/doubao/models`、`POST /ai-agent/api/doubao/multimodal/chat`、`POST /ai-agent/api/doubao/multimodal/chat/file`；千帆提供 `POST /ai-agent/api/qianfan/models`。当前没有统一的模型列表路由。

### Dubbo RPC 接口
服务通过 `tri`（Triple）协议在 `20890` 端口暴露 `LlmFacade`；消费方可使用 `@DubboReference` 注入，并调用 `chat`、`multimodalChat` 或服务端流式 `chatStream`。

### 运维接口
- `POST /ai-agent/api/nacos/config`：按传入的 `dataId` 查询 Nacos 缓存配置。
- `GET /ai-agent/api/nacos/thread-pool`：查询豆包和 DeepSeek 流式线程池的当前指标。

---

## Nacos 配置说明

所有配置通过 Nacos 管理，运行时热更新，无需重启。

### 平台凭证配置（`ai-agent-{platform}.json`）
```json
{
  "chat": {
    "apiKey": "your-api-key",
    "endpoint": "https://api.platform.com/v1/chat/completions",
    "modelCode": "model-name"
  }
}
```

部分平台存在额外配置块。例如豆包的 Responses API 多模态调用使用独立的 `multimodal` 凭证和模型配置。

### OkHttp 连接池（`ai-agent-http.json`）
```json
{
  "doubao": {
    "connectTimeoutSeconds": 10,
    "readTimeoutSeconds": 180,
    "writeTimeoutSeconds": 30,
    "maxIdleConnections": 50,
    "keepAliveMinutes": 5
  }
}
```
支持平台级代理配置（适用于 Anthropic、Gemini 等需要代理访问的平台）。

### 重试策略（`ai-agent-retry.json`）
```json
{
  "doubao": {
    "maxRetries": 3,
    "intervalMs": 500,
    "backoffMultiplier": 2.0,
    "maxWaitMs": 30000,
    "nonRetryableCodes": ["2002009", "2001001", "2002011"]
  }
}
```

| 错误码 | 含义 |
|--------|------|
| `2002009` | 认证失败 — 不重试 |
| `2001001` | 参数错误 — 不重试 |
| `2002010` | 余额不足 — 不重试 |
| `2002011` | 平台不支持的内容类型 — 不重试 |

### 线程池（`ai-agent-thread-pool.json`）
每个平台的流式请求使用独立的线程池，避免平台间相互影响，通过 Nacos 动态调整。

---

## Ollama 本地部署

Ollama 作为本地 LLM 平台被完整支持。

- **Chat & 流式**：走 OpenAI 兼容协议（`/v1/chat/completions`）
- **多模态**：支持视觉模型（`llava`、`moondream`、`minicpm-v` 等），本地需提前拉取对应模型
- **模型列表**：调用 `/api/tags` 返回本地已拉取的模型列表

```json
// Nacos 配置：ai-agent-ollama.json
{
  "chat": {
    "apiKey": "ollama",
    "endpoint": "http://localhost:11434/v1/chat/completions",
    "modelCode": "llama3.2"
  }
}
```

---

## 快速启动

### 环境要求
- Java 17+
- Spring Boot 3.x
- Nacos（用于配置中心）

### 启动
```bash
mvn spring-boot:run -pl ai-agent-starter -Dspring.profiles.active=dev
```

### 接入新平台（四步完成）
1. 新建 `XxxServiceImpl` 实现 `LlmService`，添加 `@Service("xxxServiceImpl")`
2. 新建 `XxxHttpCodeEnum` 定义该平台的 HTTP 状态码映射
3. 新建 `XxxBO` 绑定 Nacos 配置结构
4. 添加 Nacos 配置文件：`ai-agent-xxx.json`，并在 `ai-agent-http.json`、`ai-agent-retry.json` 中添加对应 key

`LlmRouter` 自动感知新平台，**无需修改任何现有代码**。

---

## 生产部署注意事项

- **代理**：通过 `ai-agent-http.json` 的 `proxy` 字段按平台配置（适用于需要代理的海外平台）

### 关联仓库（暂未开放）

本服务是更大规模分布式 AI 后端系统的一部分，以下组件作为独立仓库存在，**目前暂未开源**：

| 仓库 | 职责 |
|------|------|
| `ai-gateway` | API 网关 —— 鉴权、限流、负载均衡 |
| `ai-analysis` | 请求分析、成本追踪、用量统计 |
| `ai-orchestration` | 多 Agent 工作流编排 |
| `ai-knowledge` | RAG 知识库集成 |
| `ai-memory` | 对话记忆与上下文管理 |
| `ai-mcp` | MCP（模型上下文协议）服务端集成 |
| `ai-eval` | 模型评估与基准测试 |

> 鉴权、限流、监控均由网关层与分析层统一处理，本仓库不内置上述能力——这是架构上的刻意设计。

