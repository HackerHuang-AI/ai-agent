# 腾讯混元 Token Plan OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-28 | 官方文档: https://cloud.tencent.com/document/product/1729/105701

---

## 一、认证方式

### Token 获取
1. 登录 https://console.cloud.tencent.com/lkeap
2. 进入「Token Plan」页面，购买 Token 包后获取专属 API Key

### 请求 Header

```http
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

> ⚠️ **Token Plan 与普通 API Key 不同**：Token Plan 的 API Key 需在「Token Plan 管理」页面单独创建，与标准 TokenHub API Key 相互独立。

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://api.lkeap.cloud.tencent.com/plan/v3/chat/completions` |

> Token Plan 专属接入点，与标准 TokenHub（`tokenhub.tencentmaas.com/v1`）地址不同。

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
| `model` | string | ✅ | 当前 Token Plan 仅支持 `hy3-preview`（混元 3） |
| `messages` | array | ✅ | 与 OpenAI 格式一致，支持 `system` / `user` / `assistant` |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 2]`，默认 `0.7` |
| `max_tokens` | integer | ❌ | 最大输出 token 数，默认因模型而异 |
| `top_p` | float | ❌ | 范围 `(0, 1]`，默认 `0.9` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致（混元 3 支持） |
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
| `choices[0].finish_reason` | `stop` / `length` / `content_filter` |
| `usage.total_tokens` | 本次消耗的 Token 数（从 Token 包扣减） |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"hy3-preview","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"hy3-preview","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"hy3-preview","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | Tool Call | 说明 |
|----------|-----------|-----------|------|
| `hy3-preview` | 128K | ✅ | 腾讯混元 3 旗舰版，Token Plan 专属价格 |

> Token Plan 当前仅开放混元自有模型，第三方模型（DeepSeek、GLM 等）通过标准 TokenHub 接口访问。

---

## 七、与 TokenHub 标准接口的差异

| 差异项 | TokenHub 标准接口 | Token Plan 专属接口 |
|--------|-----------------|-------------------|
| Endpoint | `tokenhub.tencentmaas.com/v1` | `api.lkeap.cloud.tencent.com/plan/v3` |
| 计费模式 | 按量计费（每次调用实时扣费） | **包量预购**（预购 Token 包，更低单价） |
| API Key | 标准 TokenHub Key | **独立的 Token Plan Key** |
| 模型范围 | 聚合多家模型 | 当前仅混元系列 |
| 适用场景 | 弹性调用、多模型切换 | 高频稳定调用、降本优化 |

## 八、与 OpenAI 的差异点

| 差异项 | OpenAI | 腾讯混元 Token Plan |
|--------|--------|-------------------|
| Endpoint | `api.openai.com/v1` | `api.lkeap.cloud.tencent.com/plan/v3` |
| 协议 | 原生 OpenAI | **OpenAI 兼容** |
| 模型选择 | 多模型 | 当前仅 `hy3-preview` |
| 计费模式 | 按量 | 预购包量 |

---

## 九、错误码处理

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
| 401 | `LLM_AUTH_FAILED`(2002009) | Token Plan API Key 无效或已过期 |
| 400 / 422 | `PARAM_ILLEGAL`(2001001) | 请求参数非法 |
| 429 | `LLM_RATE_LIMIT`(2002011) | 调用频率超限 |
| 其他 4xx/5xx | `LLM_CALL_FAILED`(2002001) | 平台调用失败（兜底） |

> ⚠️ Token Plan 的 API Key 需在「Token Plan 管理」页面单独创建，与标准 TokenHub Key 不可混用，混用会触发 401。
> 流式接口遇到 HTTP 错误时推送 `[ERROR:{httpCode}]`；同步接口抛 `BizException`，包含错误码和平台原始信息。

