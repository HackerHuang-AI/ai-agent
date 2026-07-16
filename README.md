# AI Agent — Multi-Platform LLM Gateway

> A production-ready Spring Boot service that unifies access to 13 major LLM platforms through a single API interface, with hot-reload configuration, per-platform connection pool isolation, intelligent retry, and SSE streaming support.

---

## Supported Platforms

| Platform | Provider | Chat | Stream | Multimodal | List Models |
|----------|----------|:----:|:------:|:----------:|:-----------:|
| **Doubao** | ByteDance | ✅ | ✅ | ✅ | ✅ |
| **DeepSeek** | DeepSeek AI | ✅ | ✅ | — | — |
| **OpenAI** | OpenAI | ✅ | ✅ | — | — |
| **Anthropic** | Anthropic | ✅ | ✅ | — | — |
| **Gemini** | Google | ✅ | ✅ | — | — |
| **Qwen** | Alibaba | ✅ | ✅ | — | — |
| **Zhipu** | Zhipu AI | ✅ | ✅ | — | — |
| **Moonshot** | Moonshot AI | ✅ | ✅ | — | — |
| **Minimax** | Minimax | ✅ | ✅ | — | — |
| **Qianfan** | Baidu | ✅ | ✅ | — | ✅ |
| **Tokenhub** | Internal | ✅ | ✅ | — | — |
| **Mimo** | Xiaomi | ✅ | ✅ | — | — |
| **Ollama** | Local | ✅ | ✅ | ✅ | ✅ |

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   API Gateway Layer                  │
│         (Auth / Rate Limit / Load Balance)           │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│                  ai-agent-starter                    │
│   LlmChatController  /  XxxChatController (per-     │
│   platform dedicated endpoints remain available)     │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│               ai-agent-application                   │
│   LlmRouter → routes by platform name               │
│   XxxServiceImpl → per-platform adapter             │
│   AppRetryUtil → business-aware retry               │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│              ai-agent-infrastructure                 │
│   OkHttpConfig → per-platform connection pool       │
│   RetryConfig  → Nacos-backed retry params          │
│   NacosConfigUtil → hot-reload config               │
└─────────────────────────────────────────────────────┘
```

### Module Responsibilities

| Module | Responsibility |
|--------|---------------|
| `ai-agent-client` | Shared DTOs and facade interfaces for RPC consumers |
| `ai-agent-application` | Business logic: routing, adapters, retry, exception mapping |
| `ai-agent-infrastructure` | Technical capabilities: OkHttp pool, Nacos, thread pool |
| `ai-agent-starter` | Spring Boot entry point: controllers, global exception handler |

---

## Key Design Decisions

### 1. Per-Platform OkHttp Connection Pool
Each platform gets its own `OkHttpClient` instance with independently configured timeouts and pool size. No cross-platform interference. Config is hot-reloaded from Nacos without restart.

### 2. Dynamic Platform Routing
`LlmRouter` uses Spring's `Map<String, LlmService>` injection — bean name maps directly to platform key. **Adding a new platform requires zero changes to the router.**

### 3. Two-Layer Retry
| Layer | Class | Scope |
|-------|-------|-------|
| Sync (chat / multimodal) | `AppRetryUtil.retry()` | Full HTTP call inside lambda; exponential backoff + 10% jitter |
| Stream (chatStream) | `AppRetryUtil.retryForStream()` | Connection-establishment phase only; once streaming begins, no retry |

Non-retryable error codes (e.g. auth failure, insufficient balance) are configured per-platform in Nacos and respected by both layers.

### 4. SSE Streaming with Retry Boundary
The stream retry boundary is `response.isSuccessful()`. Network errors and HTTP 5xx before streaming begins trigger retry. Once chunks are being pushed, the connection is held open until `[DONE]` or `[ERROR]`, then released in a `finally` block.

### 5. Platform-Specific HTTP Status Code Enums
Each platform has its own `XxxHttpCodeEnum` (e.g. `DoubaoHttpCodeEnum`, `DeepseekHttpCodeEnum`) — no magic numbers anywhere in service code.

---

## API Reference

### Unified Endpoint (route by `platform` field)

**POST** `/api/llm/chat`
```json
{
  "platform": "doubao",
  "apiKey": "optional-override",
  "endpoint": "optional-override",
  "modelCode": "ep-20240101-xxxxx",
  "messages": [
    {"role": "user", "type": "TEXT", "value": "Hello"}
  ],
  "temperature": 0.7,
  "maxTokens": 2048
}
```

**POST** `/api/llm/chat/stream` — SSE, `Content-Type: text/event-stream`

**POST** `/api/llm/chat/multimodal` — image + text mixed input

### Per-Platform Dedicated Endpoints
Each platform also exposes its own controller at `/api/{platform}/chat` and `/api/{platform}/chat/stream`, preserving platform-specific capabilities.

---

## Nacos Configuration

All configuration is managed in Nacos and hot-reloaded at runtime. No restart required.

### Platform Credentials (`ai-agent-{platform}.json`)
```json
{
  "chat": {
    "apiKey": "your-api-key",
    "endpoint": "https://api.platform.com/v1/chat/completions",
    "modelCode": "model-name"
  }
}
```

### OkHttp Connection Pool (`ai-agent-http.json`)
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
Proxy configuration is supported per-platform (e.g. for Anthropic / Gemini requiring a proxy).

### Retry Policy (`ai-agent-retry.json`)
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

| Code | Meaning |
|------|---------|
| `2002009` | Auth failed — no retry |
| `2001001` | Invalid params — no retry |
| `2002010` | Insufficient balance — no retry |
| `2002011` | Platform unsupported content type — no retry |

### Thread Pool (`ai-agent-thread-pool.json`)
Each platform has an isolated stream executor thread pool, configurable via Nacos.

---

## Ollama (Local Deployment)

Ollama is fully supported as a local LLM backend.

- **Chat & Stream**: standard OpenAI-compatible protocol (`/v1/chat/completions`)
- **Multimodal**: supported for vision-capable models (`llava`, `moondream`, `minicpm-v`, etc.)
- **List Models**: calls `/api/tags` to return locally pulled models

```json
// nacos: ai-agent-ollama.json
{
  "chat": {
    "apiKey": "ollama",
    "endpoint": "http://localhost:11434/v1/chat/completions",
    "modelCode": "llama3.2"
  }
}
```

---

## Quick Start

### Prerequisites
- Java 17+
- Spring Boot 3.x
- Nacos (for configuration)

### Run
```bash
mvn spring-boot:run -pl ai-agent-starter -Dspring.profiles.active=dev
```

### Add a New Platform
1. Create `XxxServiceImpl` implementing `LlmService`, annotate with `@Service("xxxServiceImpl")`
2. Create `XxxHttpCodeEnum` for HTTP status code mapping
3. Create `XxxBO` for Nacos config binding
4. Add Nacos config files: `ai-agent-xxx.json`, entry in `ai-agent-http.json` and `ai-agent-retry.json`
5. Done — `LlmRouter` picks it up automatically, no code changes needed there

---

## Production Notes

- **Proxy**: per-platform proxy config supported in `ai-agent-http.json` (useful for Anthropic / Gemini)

### Related Repositories (Not Yet Public)

This service is part of a larger distributed AI backend system. The following components exist as separate repositories but are **not yet open-sourced**:

| Repository | Responsibility |
|------------|---------------|
| `ai-gateway` | API gateway — authentication, rate limiting, load balancing |
| `ai-analysis` | Request analytics, cost tracking, usage statistics |
| `ai-orchestration` | Multi-agent workflow orchestration |
| `ai-knowledge` | RAG knowledge base integration |
| `ai-memory` | Conversation memory and context management |
| `ai-mcp` | MCP (Model Context Protocol) server integration |
| `ai-eval` | Model evaluation and benchmarking |

> Auth, rate limiting, and monitoring are handled at the gateway and analysis layers respectively, not within this repository — by design.

