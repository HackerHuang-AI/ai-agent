# 豆包（字节跳动火山引擎）OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://www.volcengine.com/docs/82379/1263512

---

## 一、认证方式

### Token 获取
1. 登录 https://console.volcengine.com/ark
2. 在 **API Key 管理** 页面创建 API Key
3. 在 **在线推理** 页面创建模型接入点（Endpoint），获取 **endpoint_id**（格式：`ep-xxxxxxxx`）

> ⚠️ **豆包最重要的差异**：`model` 字段填的是 **endpoint_id**（如 `ep-20240101-xxxxx`），而非模型名称。
> 必须先在控制台创建接入点，才能调用对应模型。

### 请求 Header

```http
Authorization: Bearer <API_KEY>
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` |
| 模型列表 | GET | `https://ark.cn-beijing.volces.com/api/v3/models` |

---

## 三、核心入参

```json
{
  "model": "ep-20240101-xxxxx",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.8,
  "max_tokens": 4096,
  "top_p": 0.95,
  "top_k": 0,
  "frequency_penalty": 0.0,
  "presence_penalty": 0.0
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | **填 endpoint_id**，如 `ep-20240101-xxxxx`，而非模型名 |
| `messages` | array | ✅ | 与 OpenAI 格式一致 |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 1]`（注意上限是 1，非 2），默认 `0.8` |
| `max_tokens` | integer | ❌ | 最大输出 token |
| `top_p` | float | ❌ | 范围 `(0, 1)`，默认 `0.95` |
| `top_k` | integer | ❌ | **豆包特有**：top-k 采样，`0` 表示不限制 |
| `frequency_penalty` | float | ❌ | 频率惩罚，范围 `[-2, 2]` |
| `presence_penalty` | float | ❌ | 存在惩罚，范围 `[-2, 2]` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "ep-20240101-xxxxx",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Hello! How can I help you?"
      },
      "finish_reason": "stop",
      "logprobs": null
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
| `model` | 返回的是 endpoint_id，而非真实模型名 |
| `usage.prompt_tokens` | 输入 token 数 |
| `usage.completion_tokens` | 输出 token 数 |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"ep-20240101-xxxxx","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"ep-20240101-xxxxx","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"ep-20240101-xxxxx","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型（Endpoint 创建时选择）

| 模型名称 | 说明 |
|----------|------|
| `doubao-pro-32k` | 旗舰模型，32K 上下文 |
| `doubao-pro-128k` | 旗舰模型，128K 上下文 |
| `doubao-pro-256k` | 旗舰模型，256K 上下文 |
| `doubao-lite-32k` | 轻量模型，32K 上下文，低成本 |
| `doubao-lite-128k` | 轻量模型，128K 上下文 |
| `doubao-vision-pro-32k` | 视觉理解模型 |
| `doubao-1.5-pro-32k` | 新一代 pro 模型 |
| `doubao-1.5-pro-256k` | 新一代 pro 超长上下文 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | 豆包 |
|--------|--------|------|
| `model` 字段 | 填模型名，如 `gpt-4.1` | **填 endpoint_id**，如 `ep-20240101-xxxxx` |
| 接入前置步骤 | 无，有 key 直接调用 | **需先在控制台创建接入点** |
| `temperature` 范围 | `[0, 2]` | `[0, 1]`（上限不同） |
| `top_k` | 不支持 | **支持** |
| Endpoint 域名 | `api.openai.com` | `ark.cn-beijing.volces.com` |
| 返回的 `model` | 真实模型名 | endpoint_id |

---

## 八、错误码处理

### 错误响应体格式

```json
{
  "error": {
    "code": "InvalidApiKey",
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

> 流式接口（`chatStream`）遇到 HTTP 错误时，会向 SSE 通道推送 `[ERROR:{httpCode}]` 标记（如 `[ERROR:401]`）；同步接口直接抛 `BizException`，包含错误码枚举和平台原始错误信息。

