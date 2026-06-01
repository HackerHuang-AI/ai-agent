# Minimax OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://platform.minimaxi.com/document/ChatCompletion%20v2

---

## 一、认证方式

### Token 获取
1. 登录 https://platform.minimaxi.com
2. 进入 **账户信息 → API Key** 页面获取 API Key
3. 同时记录 **Group ID**（部分接口需要）

### 请求 Header

```http
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全 v2（推荐，OpenAI 兼容） | POST | `https://api.minimax.chat/v1/text/chatcompletion_v2` |
| 对话补全 Pro（旧版自有协议） | POST | `https://api.minimax.chat/v1/text/chatcompletion_pro` |

> ✅ **推荐使用 v2 接口**，协议向 OpenAI 靠拢，但仍有差异，详见下方。
> ⚠️ **旧版 Pro 接口**（chatcompletion_pro）使用完全不同的参数结构，本文档不覆盖。

---

## 三、核心入参（v2 接口）

```json
{
  "model": "abab6.5s-chat",
  "messages": [
    {"role": "system", "name": "assistant", "content": "You are a helpful assistant."},
    {"role": "user",   "name": "user",      "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.9,
  "max_tokens": 4096,
  "top_p": 0.95,
  "tools": [],
  "tool_choice": "auto",
  "mask_sensitive_info": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识，如 `abab6.5s-chat`、`abab6.5g-chat`、`abab7-preview` |
| `messages` | array | ✅ | 对话列表，**每条消息多了 `name` 字段（必填）** |
| `messages[].role` | string | ✅ | `system` / `user` / `assistant` / `tool` |
| `messages[].name` | string | ✅ | **Minimax 特有**：角色名称，通常填 `"MM智能助手"` 或 `"user"` |
| `messages[].content` | string | ✅ | 消息内容 |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0.01, 1]`，默认 `0.9`（上限 1，非 2） |
| `max_tokens` | integer | ❌ | 最大输出 token，默认 `4096`，最大 `16384` |
| `top_p` | float | ❌ | 范围 `(0, 1)`，默认 `0.95` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 基本一致 |
| `mask_sensitive_info` | boolean | ❌ | **Minimax 特有**：是否对输出中的敏感信息（手机号等）打码，默认 `false` |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "abab6.5s-chat",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "name": "MM智能助手",
        "content": "Hello! How can I help you?"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 20,
    "completion_tokens": 10,
    "total_tokens": 30
  },
  "base_resp": {
    "status_code": 0,
    "status_msg": "success"
  }
}
```

| 字段 | 说明 |
|------|------|
| `choices[0].message.content` | 模型回复内容 |
| `choices[0].message.name` | **Minimax 特有**：助手角色名 |
| `choices[0].finish_reason` | `stop` / `length` / `tool_calls` |
| `base_resp.status_code` | **Minimax 特有**：业务状态码，`0` 为成功，非 0 为错误 |
| `base_resp.status_msg` | **Minimax 特有**：业务状态信息 |

> ⚠️ HTTP 状态码为 200 不等于业务成功，需检查 `base_resp.status_code` 是否为 0。

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"abab6.5s-chat","choices":[{"index":0,"delta":{"role":"assistant","name":"MM智能助手","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"abab6.5s-chat","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"abab6.5s-chat","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `choices[0].delta.name` | **Minimax 特有**：助手角色名（首帧携带） |
| `usage`（最后正式帧） | Minimax 在最后一帧（finish_reason 非空时）附带 usage |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 视觉 | Tool Call | 说明 |
|----------|-----------|------|-----------|------|
| `abab6.5s-chat` | 245K | ❌ | ✅ | 主力模型，高性价比 |
| `abab6.5g-chat` | 8K | ❌ | ❌ | 高速轻量 |
| `abab6.5t-chat` | 8K | ❌ | ❌ | 极速模型 |
| `abab7-preview` | 245K | ✅ | ✅ | 旗舰模型，支持视觉 |
| `MiniMax-Text-01` | 1M | ❌ | ✅ | 超长上下文 |
| `MiniMax-VL-01` | 1M | ✅ | ✅ | 视觉旗舰 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | Minimax |
|--------|--------|---------|
| `messages[].name` | 可选 | **必填**（每条消息都要有角色名） |
| `temperature` 范围 | `[0, 2]` | `[0.01, 1]`（不能为 0，上限为 1） |
| `base_resp` 字段 | 不存在 | **特有**，HTTP 200 仍需检查业务状态码 |
| 返回消息中的 `name` | 不存在 | 助手消息含 `name` 字段 |
| `mask_sensitive_info` | 不存在 | **特有**，敏感信息脱敏开关 |
| 流式 `usage` 时机 | 需额外参数开启 | 在最后正式帧自动附带 |
| Endpoint 路径 | `/v1/chat/completions` | `/v1/text/chatcompletion_v2` |

