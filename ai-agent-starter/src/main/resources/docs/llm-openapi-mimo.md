# 小米 MiMo OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-28 | 官方文档: https://api.xiaomimimo.com/docs

---

## 一、认证方式

### Token 获取
1. 登录 https://api.xiaomimimo.com
2. 进入「API Keys」页面创建 API Key

### 请求 Header

```http
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://api.xiaomimimo.com/v1/chat/completions` |
| 模型列表 | GET | `https://api.xiaomimimo.com/v1/models` |

> MiMo 采用标准 OpenAI 兼容协议，可直接替换 OpenAI SDK 的 `base_url`。

---

## 三、核心入参

```json
{
  "model": "mimo-v2.5-pro",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.7,
  "max_tokens": 8192,
  "top_p": 0.9,
  "tools": [],
  "tool_choice": "auto"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识：`mimo-v2.5-pro` / `mimo-v2.5` |
| `messages` | array | ✅ | 与 OpenAI 格式一致，支持 `system` / `user` / `assistant` |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 2]`，默认 `0.7` |
| `max_tokens` | integer | ❌ | 最大输出 token 数，默认因模型而异 |
| `top_p` | float | ❌ | 范围 `(0, 1]`，默认 `0.9` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-xxxxxxxxxxxx",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "mimo-v2.5-pro",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you?",
        "reasoning_content": "Let me think about this..."
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
| `choices[0].message.reasoning_content` | **MiMo 特有**：推理过程内容，仅推理增强版模型输出 |
| `choices[0].finish_reason` | `stop` / `length` / `tool_calls` |
| `usage.prompt_tokens` | 输入 token 数 |
| `usage.completion_tokens` | 输出 token 数（含推理 token） |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"mimo-v2.5-pro","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"Let me think...","content":null},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"mimo-v2.5-pro","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"mimo-v2.5-pro","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"mimo-v2.5-pro","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.reasoning_content` | **MiMo 特有**：推理过程 chunk，先于 `content` 输出 |
| `choices[0].delta.content` | 当前 chunk 文本 |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 推理能力 | Tool Call | 说明 |
|----------|-----------|---------|-----------|------|
| `mimo-v2.5-pro` | 128K | ✅ | ✅ | 旗舰推理模型，含思维链输出 |
| `mimo-v2.5` | 128K | ❌ | ✅ | 标准对话模型，速度更快 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | 小米 MiMo |
|--------|--------|----------|
| Endpoint | `api.openai.com/v1` | `api.xiaomimimo.com/v1` |
| 协议 | 原生 OpenAI | **OpenAI 兼容**，差异极低 |
| `reasoning_content` | 不存在 | **特有**：推理增强版输出思维链（类似 DeepSeek reasoner） |
| 模型数量 | 多 | 当前仅 `mimo-v2.5` 系列 |
| 适用场景 | 通用 | 深度推理、数学、代码 |

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
| 400 / 422 | `PARAM_ILLEGAL`(2001001) | 请求参数非法 |
| 429 | `LLM_RATE_LIMIT`(2002011) | 调用频率超限 |
| 其他 4xx/5xx | `LLM_CALL_FAILED`(2002001) | 平台调用失败（兜底） |

> 流式接口遇到 HTTP 错误时推送 `[ERROR:{httpCode}]`；同步接口抛 `BizException`，包含错误码和平台原始信息。

