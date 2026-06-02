# Google（Gemini）OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-02 | 官方文档: https://ai.google.dev/api/generate-content

---

## 一、认证方式

### Token 获取
1. 登录 https://aistudio.google.com
2. 点击 **Get API key** 创建 API Key（格式：`AIza...`）

### 两种调用方式对比

| 方式 | 说明 | 推荐度 |
|------|------|--------|
| **OpenAI 兼容接口** | URL 参数传 key，Header 格式与 OpenAI 一致 | ✅ 推荐（便于统一适配） |
| **原生 Gemini API** | URL 参数传 key，请求体结构完全不同 | 原生能力更全 |

### 方式一：OpenAI 兼容接口 Header

```http
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

### 方式二：原生 API（URL 参数传 key）

```http
Content-Type: application/json
```

请求 URL 末尾附加：`?key=<API_KEY>`

```
https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=AIza...
```

---

## 二、Endpoint

### 2.1 OpenAI 兼容接口（推荐）

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` |
| 模型列表 | GET | `https://generativelanguage.googleapis.com/v1beta/openai/models` |

### 2.2 原生 Gemini API

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步） | POST | `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key=<KEY>` |
| 对话补全（流式） | POST | `https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?key=<KEY>` |
| 模型列表 | GET | `https://generativelanguage.googleapis.com/v1beta/models?key=<KEY>` |

---

## 三、核心入参

### 3.1 OpenAI 兼容接口（与 OpenAI 格式一致）

```json
{
  "model": "gemini-2.0-flash",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 1.0,
  "max_tokens": 4096,
  "top_p": 0.95,
  "tools": [],
  "tool_choice": "auto"
}
```

入参格式与 OpenAI 基本一致，差异点见第七节。

### 3.2 原生 Gemini API 入参

```json
{
  "contents": [
    {
      "role": "user",
      "parts": [{"text": "Hello!"}]
    }
  ],
  "systemInstruction": {
    "parts": [{"text": "You are a helpful assistant."}]
  },
  "generationConfig": {
    "temperature": 1.0,
    "maxOutputTokens": 4096,
    "topP": 0.95,
    "topK": 40,
    "candidateCount": 1,
    "stopSequences": []
  },
  "tools": []
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `contents` | array | ✅ | 对话内容，**不是 `messages`**，每条含 `role` 和 `parts` |
| `contents[].role` | string | ✅ | `user` / `model`（**不是 `assistant`**） |
| `contents[].parts` | array | ✅ | 内容数组，文本为 `[{"text": "..."}]`，图片为 `[{"inlineData":...}]` |
| `systemInstruction` | object | ❌ | **独立系统提示**，不在 `contents` 中 |
| `generationConfig` | object | ❌ | **生成参数集合**（对应 OpenAI 的顶层字段） |
| `generationConfig.temperature` | float | ❌ | 随机性，范围 `[0, 2]` |
| `generationConfig.maxOutputTokens` | integer | ❌ | 最大输出 token（对应 OpenAI 的 `max_tokens`） |
| `generationConfig.topP` | float | ❌ | nucleus sampling |
| `generationConfig.topK` | integer | ❌ | top-k 采样 |
| `generationConfig.candidateCount` | integer | ❌ | 返回候选数量，通常为 `1` |

---

## 四、核心出参（同步）

### 4.1 OpenAI 兼容接口出参（与 OpenAI 一致）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "gemini-2.0-flash",
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

### 4.2 原生 Gemini API 出参

```json
{
  "candidates": [
    {
      "content": {
        "role": "model",
        "parts": [{"text": "Hello! How can I help you?"}]
      },
      "finishReason": "STOP",
      "index": 0,
      "safetyRatings": [...]
    }
  ],
  "usageMetadata": {
    "promptTokenCount": 20,
    "candidatesTokenCount": 10,
    "totalTokenCount": 30
  },
  "modelVersion": "gemini-2.0-flash"
}
```

| 字段 | 说明 |
|------|------|
| `candidates[0].content.parts[0].text` | 实际回复文本（**非 `choices`，非 `message.content`**） |
| `candidates[0].content.role` | `model`（**不是 `assistant`**） |
| `candidates[0].finishReason` | `STOP` / `MAX_TOKENS` / `SAFETY` / `RECITATION` / `OTHER` |
| `usageMetadata.promptTokenCount` | 输入 token 数 |
| `usageMetadata.candidatesTokenCount` | 输出 token 数 |
| `safetyRatings` | **Gemini 特有**：内容安全评分 |

---

## 五、核心出参（流式 SSE）

### 5.1 OpenAI 兼容接口流式（与 OpenAI 格式一致）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"gemini-2.0-flash","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"gemini-2.0-flash","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

### 5.2 原生 API 流式（JSON Lines，非 SSE）

原生 streamGenerateContent 返回 **JSON 数组流**，每个 chunk 格式与同步出参相同：

```
[
{"candidates":[{"content":{"role":"model","parts":[{"text":"Hello"}]},"finishReason":null}]}
,
{"candidates":[{"content":{"role":"model","parts":[{"text":"!"}]},"finishReason":"STOP"}],"usageMetadata":{...}}
]
```

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 视觉 | Tool Call | 说明 |
|----------|-----------|------|-----------|------|
| `gemini-2.0-flash` | 1M | ✅ | ✅ | 主力模型，速度快 |
| `gemini-2.0-flash-lite` | 1M | ✅ | ✅ | 轻量低成本 |
| `gemini-2.5-flash-preview` | 1M | ✅ | ✅ | 2.5 系列预览版 |
| `gemini-2.5-pro-preview` | 1M | ✅ | ✅ | 2.5 旗舰预览版 |
| `gemini-1.5-pro` | 2M | ✅ | ✅ | 超长上下文旗舰 |
| `gemini-1.5-flash` | 1M | ✅ | ✅ | 1.5 均衡版 |
| `text-embedding-004` | — | ❌ | ❌ | 文本 Embedding 模型 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | Google Gemini |
|--------|--------|---------------|
| 认证方式（兼容接口） | `Authorization: Bearer sk-xxx` | `Authorization: Bearer AIza...` |
| 认证方式（原生接口） | Header | **URL 参数 `?key=AIza...`** |
| Endpoint（兼容） | `api.openai.com/v1/chat/completions` | `generativelanguage.googleapis.com/v1beta/openai/chat/completions` |
| 原生接口请求体 | `messages` 数组 | **`contents` 数组**，`parts` 嵌套结构 |
| 原生 `role` 值 | `user/assistant/system` | `user/model`（**`assistant` 改名为 `model`**） |
| 原生 `system` 消息 | 在 messages 中 | **独立的 `systemInstruction` 字段** |
| 原生生成参数 | 顶层字段 | **包裹在 `generationConfig` 对象中** |
| 原生 `max_tokens` | `max_tokens` | `generationConfig.maxOutputTokens` |
| 原生出参结构 | `choices[0].message.content` | **`candidates[0].content.parts[0].text`** |
| 原生 `finish_reason` 值 | `stop/length` | `STOP/MAX_TOKENS/SAFETY/RECITATION` |
| 原生 token 字段 | `prompt_tokens/completion_tokens` | `promptTokenCount/candidatesTokenCount` |
| 原生流式格式 | SSE | **JSON 数组流（非 SSE）** |
| `safetyRatings` | 无 | **特有**，内容安全评分 |

