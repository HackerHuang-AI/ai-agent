# Qwen（阿里云百炼）OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://help.aliyun.com/zh/model-studio/developer-reference/use-qwen-by-calling-api

---

## 一、认证方式

### Token 获取
1. 登录 https://bailian.console.aliyun.com
2. 进入 API Key 管理页面创建 API Key（格式：`sk-...`）

### 请求 Header

```http
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（OpenAI 兼容，同步 + 流式） | POST | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` |
| 模型列表 | GET | `https://dashscope.aliyuncs.com/compatible-mode/v1/models` |

> ⚠️ 注意：阿里云还提供原生 DashScope 协议（`/api/v1/services/aigc/...`），本文档仅记录 OpenAI 兼容模式。

---

## 三、核心入参

```json
{
  "model": "qwen-max",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.7,
  "max_tokens": 4096,
  "top_p": 0.9,
  "tools": [],
  "tool_choice": "auto",
  "enable_search": false,
  "response_format": {"type": "text"}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识，如 `qwen-max`、`qwen-plus`、`qwen-turbo` |
| `messages` | array | ✅ | 与 OpenAI 格式一致 |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 2]`，默认 `0.7` |
| `max_tokens` | integer | ❌ | 最大输出 token，默认因模型而异 |
| `top_p` | float | ❌ | 范围 `(0, 1)`，默认 `0.9` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |
| `enable_search` | boolean | ❌ | **Qwen 特有**：是否开启联网搜索，默认 `false` |
| `response_format` | object | ❌ | 输出格式：`{"type":"text"}` / `{"type":"json_object"}` |
| `stream_options` | object | ❌ | 流式选项，`{"include_usage":true}` 可在流式中返回 usage |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "qwen-max",
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
| `choices[0].finish_reason` | `stop` / `length` / `tool_calls` / `null` |
| `usage.prompt_tokens` | 输入 token 数 |
| `usage.completion_tokens` | 输出 token 数 |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"qwen-max","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"qwen-max","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"qwen-max","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `usage`（最后一帧） | 需设置 `stream_options.include_usage=true` 才会返回 |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 视觉 | Tool Call | 说明 |
|----------|-----------|------|-----------|------|
| `qwen-max` | 32K | ❌ | ✅ | 最强效果 |
| `qwen-max-latest` | 32K | ❌ | ✅ | 始终指向最新 qwen-max |
| `qwen-plus` | 128K | ❌ | ✅ | 效果与速度均衡 |
| `qwen-turbo` | 1M | ❌ | ✅ | 极速低成本 |
| `qwen-vl-max` | 32K | ✅ | ✅ | 视觉理解旗舰 |
| `qwen-vl-plus` | 32K | ✅ | ✅ | 视觉理解均衡 |
| `qwen3-235b-a22b` | 128K | ❌ | ✅ | Qwen3 最大 MoE 模型 |
| `qwen3-30b-a3b` | 128K | ❌ | ✅ | Qwen3 轻量 MoE |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | Qwen |
|--------|--------|------|
| Endpoint | `api.openai.com` | `dashscope.aliyuncs.com/compatible-mode` |
| `enable_search` | 不存在 | **特有**，开启联网搜索能力 |
| `temperature` 默认值 | `1.0` | `0.7` |
| `top_p` 默认值 | `1.0` | `0.9` |
| 视觉模型命名 | `gpt-4o` 内置视觉 | 视觉能力需选 `qwen-vl-*` 系列 |
| 思考模型 | `o` 系列 | `qwq-32b`（通过 `enable_thinking` 控制） |

---

## 八、错误码处理

### 错误响应体格式

```json
{
  "error": {
    "code": "InvalidApiKey",
    "message": "The API key is invalid."
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

