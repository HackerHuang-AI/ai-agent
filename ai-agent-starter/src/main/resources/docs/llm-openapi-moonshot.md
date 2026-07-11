# Moonshot（Kimi）OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://platform.moonshot.cn/docs/api/chat

---

## 一、认证方式

### Token 获取
1. 登录 https://platform.moonshot.cn
2. 进入 **API Keys** 页面创建 API Key（格式：`sk-...`）

### 请求 Header

```http
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://api.moonshot.cn/v1/chat/completions` |
| 模型列表 | GET | `https://api.moonshot.cn/v1/models` |

---

## 三、核心入参

```json
{
  "model": "moonshot-v1-8k",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.3,
  "max_tokens": 4096,
  "top_p": 1.0,
  "tools": [],
  "tool_choice": "auto"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识：`moonshot-v1-8k` / `moonshot-v1-32k` / `moonshot-v1-128k` |
| `messages` | array | ✅ | 与 OpenAI 格式完全一致 |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 1]`，推荐 `0.3`（官方建议），默认 `0` |
| `max_tokens` | integer | ❌ | 最大输出 token，不超过模型上下文限制 |
| `top_p` | float | ❌ | 范围 `(0, 1]`，默认 `1` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |
| `tool_choice` | string | ❌ | `auto` / `none` / `required` |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "moonshot-v1-8k",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 20,
    "completion_tokens": 10,
    "total_tokens": 30
  }
}
```

| 字段 | 说明 |
|------|------|
| `choices[0].message.content` | 模型回复内容 |
| `choices[0].finish_reason` | `stop` / `length` / `tool_calls` |
| `usage.prompt_tokens` | 输入 token 数 |
| `usage.completion_tokens` | 输出 token 数 |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"moonshot-v1-8k","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"moonshot-v1-8k","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 说明 |
|----------|-----------|------|
| `moonshot-v1-8k` | 8K | 轻量快速，适合短对话 |
| `moonshot-v1-32k` | 32K | 均衡，适合长文档 |
| `moonshot-v1-128k` | 128K | 超长上下文，适合长文分析 |
| `kimi-latest` | 128K | 始终指向最新 Kimi 模型 |

> ℹ️ 上下文窗口同时限制输入+输出总量，选择模型时注意控制总 token 数。

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | Moonshot |
|--------|--------|----------|
| 协议格式 | 原生 | **高度兼容，几乎无差异** |
| `temperature` 范围 | `[0, 2]` | `[0, 1]`（上限不同） |
| `temperature` 默认值 | `1` | `0`（官方推荐 `0.3`） |
| 模型维度 | 按能力区分 | 按**上下文长度**区分（8k/32k/128k） |
| 流式 `usage` | 需额外参数 | 默认不返回 |
| Endpoint | `api.openai.com` | `api.moonshot.cn` |

> ✅ Moonshot 是所有平台中与 OpenAI 协议最接近的，几乎可以做到零改动切换。

---

## 八、错误码处理

### 错误响应体格式

```json
{
  "error": {
    "code": "invalid_api_key",
    "message": "Invalid API key provided."
  }
}
```

### HTTP 状态码 → 系统错误码映射

| HTTP 状态码 | 系统错误码 | 说明 |
|------------|-----------|------|
| 401 | `LLM_AUTH_FAILED`(2002009) | API Key 无效或已过期 |
| 402 | `LLM_INSUFFICIENT_BALANCE`(2002010) | 账号余额不足 |
| 400 / 422 | `PARAM_ILLEGAL`(2001001) | 请求参数非法 |
| 429 | `LLM_RATE_LIMIT`(2002011) | 调用频率超限 |
| 其他 4xx/5xx | `LLM_CALL_FAILED`(2002001) | 平台调用失败（兜底） |

> 流式接口（`chatStream`）遇到 HTTP 错误时，会向 SSE 通道推送 `[ERROR:{httpCode}]` 标记；同步接口直接抛 `BizException`，包含错误码枚举和平台原始错误信息。

