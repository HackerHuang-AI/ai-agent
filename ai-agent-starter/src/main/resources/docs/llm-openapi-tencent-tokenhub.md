# 腾讯 TokenHub OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-28 | 官方文档: https://cloud.tencent.com/document/product/1729

---

## 一、认证方式

### Token 获取
1. 登录 https://console.cloud.tencent.com/lkeap
2. 进入「API Key 管理」页面创建 API Key

### 请求 Header

```http
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://tokenhub.tencentmaas.com/v1/chat/completions` |
| 模型列表 | GET | `https://tokenhub.tencentmaas.com/v1/models` |

> TokenHub 是腾讯云 MaaS（Model as a Service）平台的统一接入层，采用标准 OpenAI 兼容协议，聚合了混元、DeepSeek、GLM、Kimi、MiniMax 等多家模型。

---

## 三、核心入参

```json
{
  "model": "hy3-preview",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.7,
  "max_tokens": 4096,
  "top_p": 0.9,
  "tools": [],
  "tool_choice": "auto"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识，如 `hy3-preview`、`deepseek-v4-pro` 等 |
| `messages` | array | ✅ | 与 OpenAI 格式一致，支持 `system` / `user` / `assistant` |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 2]`，默认 `0.7` |
| `max_tokens` | integer | ❌ | 最大输出 token 数，默认因模型而异 |
| `top_p` | float | ❌ | 范围 `(0, 1]`，默认 `0.9` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |
| `tool_choice` | string / object | ❌ | `auto` / `none` / 指定工具对象 |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-xxxxxxxxxxxx",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "hy3-preview",
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
| `choices[0].finish_reason` | `stop` / `length` / `tool_calls` / `content_filter` |
| `usage.prompt_tokens` | 输入 token 数 |
| `usage.completion_tokens` | 输出 token 数 |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"hy3-preview","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"hy3-preview","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"hy3-preview","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | Tool Call | 说明 |
|----------|-----------|-----------|------|
| `hy3-preview` | 128K | ✅ | 腾讯混元 3 旗舰版 |
| `deepseek-v4-pro` | 64K | ✅ | TokenHub 托管 DeepSeek V4 Pro |
| `deepseek-v4-flash` | 64K | ✅ | TokenHub 托管 DeepSeek V4 Flash |
| `glm-5.2` | 128K | ✅ | TokenHub 托管 GLM-5.2 |
| `glm-5.1` | 128K | ✅ | TokenHub 托管 GLM-5.1 |
| `glm-5` | 128K | ✅ | TokenHub 托管 GLM-5 |
| `glm-5-turbo` | 128K | ✅ | TokenHub 托管 GLM-5 Turbo |
| `kimi-k2.7-code` | 128K | ✅ | TokenHub 托管 Kimi K2.7 Code |
| `kimi-k2.6` | 128K | ✅ | TokenHub 托管 Kimi K2.6 |
| `kimi-k2.5` | 128K | ✅ | TokenHub 托管 Kimi K2.5 |
| `minimax-m3` | 1M | ✅ | TokenHub 托管 MiniMax M3 |
| `minimax-m2.7` | 256K | ✅ | TokenHub 托管 MiniMax M2.7 |
| `minimax-m2.5` | 256K | ✅ | TokenHub 托管 MiniMax M2.5 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | 腾讯 TokenHub |
|--------|--------|--------------|
| Endpoint | `api.openai.com/v1` | `tokenhub.tencentmaas.com/v1` |
| 协议 | 原生 OpenAI | **OpenAI 兼容**，差异极低 |
| 模型来源 | OpenAI 自有 | **聚合平台**：混元 + DeepSeek + GLM + Kimi + MiniMax 等 |
| API Key 格式 | `sk-...` | `sk-...`（与 OpenAI 格式相同） |
| 计费单位 | 按 token | 按 token（各模型价格独立） |

