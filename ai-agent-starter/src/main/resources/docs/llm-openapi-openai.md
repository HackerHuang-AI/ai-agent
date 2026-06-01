# OpenAI OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://platform.openai.com/docs/api-reference/chat

---

## 一、认证方式

### Token 获取
1. 登录 https://platform.openai.com
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
| 对话补全（同步 + 流式） | POST | `https://api.openai.com/v1/chat/completions` |
| 模型列表 | GET | `https://api.openai.com/v1/models` |

---

## 三、核心入参

```json
{
  "model": "gpt-4.1",
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
| `model` | string | ✅ | 模型标识，如 `gpt-4.1`、`gpt-4o-mini`、`gpt-5` |
| `messages` | array | ✅ | 对话消息列表，每条含 `role` 和 `content` |
| `messages[].role` | string | ✅ | 角色：`system` / `user` / `assistant` / `tool` |
| `messages[].content` | string/array | ✅ | 文本内容；多模态时为数组（含 image_url） |
| `stream` | boolean | ❌ | 是否流式输出，默认 `false` |
| `temperature` | float | ❌ | 随机性，范围 `[0, 2]`，默认 `1` |
| `max_tokens` | integer | ❌ | 最大输出 token 数 |
| `top_p` | float | ❌ | nucleus sampling，范围 `(0, 1]`，默认 `1` |
| `frequency_penalty` | float | ❌ | 频率惩罚，范围 `[-2, 2]`，默认 `0` |
| `presence_penalty` | float | ❌ | 存在惩罚，范围 `[-2, 2]`，默认 `0` |
| `tools` | array | ❌ | Function Call 工具列表 |
| `tool_choice` | string/object | ❌ | 工具调用策略：`auto` / `none` / `required` |
| `response_format` | object | ❌ | 指定输出格式，如 `{"type":"json_object"}` |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "gpt-4.1",
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
| `choices[0].finish_reason` | 结束原因：`stop` / `length` / `tool_calls` / `content_filter` |
| `usage.prompt_tokens` | 输入 token 数 |
| `usage.completion_tokens` | 输出 token 数 |
| `usage.total_tokens` | 总 token 数 |

---

## 五、核心出参（流式 SSE）

请求时 `"stream": true`，响应为 SSE 格式，每行一个事件：

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","created":1748736000,"model":"gpt-4.1","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","created":1748736000,"model":"gpt-4.1","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","created":1748736000,"model":"gpt-4.1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 的文本片段，可能为空字符串 |
| `choices[0].finish_reason` | 非 null 时表示流结束 |
| `data: [DONE]` | 流式结束标志，收到后关闭连接 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 视觉 | Tool Call | 说明 |
|----------|-----------|------|-----------|------|
| `gpt-4o-mini` | 128K | ✅ | ✅ | 轻量高性价比 |
| `gpt-4.1` | 1M | ✅ | ✅ | 主力模型 |
| `gpt-4.1-mini` | 1M | ✅ | ✅ | 4.1 轻量版 |
| `gpt-4.1-nano` | 1M | ✅ | ✅ | 4.1 极速版 |
| `gpt-5` | 200K | ✅ | ✅ | 旗舰模型 |
| `gpt-5-mini` | 200K | ✅ | ✅ | 旗舰轻量版 |

---

## 七、注意事项

- `max_tokens` 已废弃，新版推荐使用 `max_completion_tokens`（含 reasoning token）
- `gpt-5` 系列的 `temperature` 固定为 `1`，传其他值会被忽略
- 流式模式下 `usage` 默认不返回，需额外传 `"stream_options": {"include_usage": true}`

