# Deepseek OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://api-docs.deepseek.com/zh-cn/

---

## 一、认证方式

### Token 获取
1. 登录 https://platform.deepseek.com
2. 进入 API Keys 页面创建 API Key（格式：`sk-...`）

### 请求 Header

```http
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://api.deepseek.com/chat/completions` |
| 模型列表 | GET | `https://api.deepseek.com/models` |

---

## 三、核心入参

```json
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 1.0,
  "max_tokens": 4096,
  "top_p": 1.0,
  "frequency_penalty": 0.0,
  "presence_penalty": 0.0,
  "tools": [],
  "tool_choice": "auto"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识：`deepseek-chat` / `deepseek-reasoner` |
| `messages` | array | ✅ | 对话消息列表，格式与 OpenAI 完全一致 |
| `messages[].role` | string | ✅ | `system` / `user` / `assistant` / `tool` |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 2]`，默认 `1`；**使用 reasoner 模型时不可设置** |
| `max_tokens` | integer | ❌ | 最大输出 token 数，默认 `4096`，上限 `8192` |
| `top_p` | float | ❌ | 范围 `(0, 1]`，默认 `1` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "deepseek-chat",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you?",
        "reasoning_content": null
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 20,
    "completion_tokens": 10,
    "total_tokens": 30,
    "prompt_cache_hit_tokens": 0,
    "prompt_cache_miss_tokens": 20
  }
}
```

| 字段 | 说明 |
|------|------|
| `choices[0].message.content` | 模型回复内容 |
| `choices[0].message.reasoning_content` | **Deepseek 特有**：`deepseek-reasoner` 模型的思维链内容，其他模型为 `null` |
| `choices[0].finish_reason` | 结束原因：`stop` / `length` / `tool_calls` / `content_filter` |
| `usage.prompt_cache_hit_tokens` | **Deepseek 特有**：命中 KV 缓存的 token 数（命中可降低计费） |
| `usage.prompt_cache_miss_tokens` | **Deepseek 特有**：未命中缓存的 token 数 |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"deepseek-chat","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello","reasoning_content":null},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"deepseek-chat","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"deepseek-chat","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `choices[0].delta.reasoning_content` | **Deepseek 特有**：reasoner 模型的思维链 chunk，先于 `content` 输出 |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 说明 |
|----------|-----------|------|
| `deepseek-chat` | 64K | 通用对话模型（V3），支持 Function Call |
| `deepseek-reasoner` | 64K | 推理模型（R1），输出含思维链，不支持 temperature |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | Deepseek |
|--------|--------|----------|
| Endpoint 路径 | `/v1/chat/completions` | `/chat/completions`（无 `/v1` 前缀） |
| `reasoning_content` 字段 | 不存在 | `deepseek-reasoner` 模型特有，思维链内容 |
| `prompt_cache_*_tokens` | 不存在 | 特有，用于 KV 缓存计费统计 |
| `temperature` on reasoner | 可设置 | **不可设置**，传入会报错 |
| 模型数量 | 多 | 仅 `deepseek-chat` 和 `deepseek-reasoner` |

