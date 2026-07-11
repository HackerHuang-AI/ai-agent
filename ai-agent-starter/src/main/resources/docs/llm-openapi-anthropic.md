# Anthropic（Claude）OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-02 | 官方文档: https://docs.anthropic.com/en/api/messages

---

## 一、认证方式

### Token 获取
1. 登录 https://console.anthropic.com
2. 进入 **API Keys** 页面创建 API Key（格式：`sk-ant-...`）

### 请求 Header

```http
x-api-key: sk-ant-xxxxxxxxxxxxxxxx
anthropic-version: 2023-06-01
Content-Type: application/json
```

> ⚠️ **与其他平台最大的认证差异**：
> - 不使用 `Authorization: Bearer`，而是用 **`x-api-key`** 独立 Header
> - **必须**携带 `anthropic-version` Header，否则请求被拒绝，当前稳定版本为 `2023-06-01`

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://api.anthropic.com/v1/messages` |
| 模型列表 | GET | `https://api.anthropic.com/v1/models` |

> ⚠️ 接口路径为 `/v1/messages`，**不是** `/v1/chat/completions`。

---

## 三、核心入参

```json
{
  "model": "claude-sonnet-4-5",
  "max_tokens": 4096,
  "system": "You are a helpful assistant.",
  "messages": [
    {"role": "user", "content": "Hello!"}
  ],
  "temperature": 1.0,
  "top_p": 1.0,
  "top_k": 0,
  "stream": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识，如 `claude-opus-4-5`、`claude-sonnet-4-5`、`claude-haiku-4-5` |
| `max_tokens` | integer | ✅ | **必填**（OpenAI 中可选），最大输出 token 数 |
| `messages` | array | ✅ | 对话列表，**不含 system 消息**（system 单独字段） |
| `messages[].role` | string | ✅ | 仅支持 `user` / `assistant`，**不支持 `system`** |
| `messages[].content` | string/array | ✅ | 文本或多模态内容数组 |
| `system` | string | ❌ | **Anthropic 特有**：系统提示词，独立于 messages 之外 |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 1]`，默认 `1`（上限 1，非 2） |
| `max_tokens` | integer | ✅ | 必填，OpenAI 中为可选 |
| `top_p` | float | ❌ | nucleus sampling，与 `temperature` 建议只用一个 |
| `top_k` | integer | ❌ | top-k 采样，`0` 表示不限制 |
| `tools` | array | ❌ | Function Call，结构与 OpenAI 有差异，见下方 |
| `tool_choice` | object | ❌ | `{"type":"auto"}` / `{"type":"any"}` / `{"type":"tool","name":"xxx"}` |
| `metadata` | object | ❌ | `{"user_id": "xxx"}`，用于滥用监控 |

### tools 字段结构（与 OpenAI 差异）

```json
{
  "tools": [
    {
      "name": "get_weather",
      "description": "获取天气信息",
      "input_schema": {
        "type": "object",
        "properties": {
          "location": {"type": "string", "description": "城市名"}
        },
        "required": ["location"]
      }
    }
  ]
}
```

> OpenAI 的 `parameters` 在 Anthropic 中叫 `input_schema`。

---

## 四、核心出参（同步）

```json
{
  "id": "msg_abc123",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-5",
  "content": [
    {
      "type": "text",
      "text": "Hello! How can I help you?"
    }
  ],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 20,
    "output_tokens": 10
  }
}
```

| 字段 | 说明 |
|------|------|
| `content` | **数组结构**（OpenAI 是 `choices[0].message.content` 字符串），每个元素有 `type` 和 `text` |
| `content[0].text` | 实际回复文本 |
| `stop_reason` | 结束原因：`end_turn` / `max_tokens` / `stop_sequence` / `tool_use` |
| `usage.input_tokens` | 输入 token 数（OpenAI 叫 `prompt_tokens`） |
| `usage.output_tokens` | 输出 token 数（OpenAI 叫 `completion_tokens`） |

> ⚠️ **结构差异显著**：
> - 无 `choices` 数组，直接在顶层有 `content` 数组
> - `stop_reason` 对应 OpenAI 的 `finish_reason`，但值不同（`end_turn` vs `stop`）
> - token 字段名不同：`input_tokens` / `output_tokens`

---

## 五、核心出参（流式 SSE）

Anthropic 流式基于 SSE，但**事件类型比 OpenAI 更丰富**：

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_abc","type":"message","role":"assistant","model":"claude-sonnet-4-5","content":[],"stop_reason":null,"usage":{"input_tokens":20,"output_tokens":0}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"!"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":10}}

event: message_stop
data: {"type":"message_stop"}
```

| 事件类型 | 说明 |
|----------|------|
| `message_start` | 流开始，包含消息元数据和输入 token 数 |
| `content_block_start` | 内容块开始 |
| `content_block_delta` | **文本 chunk**，`delta.text` 为实际内容 |
| `content_block_stop` | 内容块结束 |
| `message_delta` | 包含 `stop_reason` 和输出 token 数 |
| `message_stop` | 流完全结束（对应 OpenAI 的 `data: [DONE]`） |

> ⚠️ **没有 `data: [DONE]`**，流结束标志是 `event: message_stop`。

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 视觉 | Tool Call | 说明 |
|----------|-----------|------|-----------|------|
| `claude-haiku-4-5` | 200K | ✅ | ✅ | 最快最轻量 |
| `claude-sonnet-4-5` | 200K | ✅ | ✅ | 主力均衡模型 |
| `claude-sonnet-4-6` | 200K | ✅ | ✅ | Sonnet 升级版 |
| `claude-opus-4-5` | 200K | ✅ | ✅ | 旗舰模型 |
| `claude-3-5-haiku-20241022` | 200K | ✅ | ✅ | Claude 3.5 轻量 |
| `claude-3-5-sonnet-20241022` | 200K | ✅ | ✅ | Claude 3.5 主力 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | Anthropic |
|--------|--------|-----------|
| 认证 Header | `Authorization: Bearer sk-xxx` | **`x-api-key: sk-ant-xxx`**（完全不同的 Header 字段） |
| 版本 Header | 无 | **`anthropic-version: 2023-06-01`（必填）** |
| Endpoint | `/v1/chat/completions` | **`/v1/messages`** |
| `system` 消息 | 放在 `messages` 数组里，`role=system` | **单独的顶层 `system` 字段** |
| `messages` 支持的 role | `system/user/assistant/tool` | **仅 `user/assistant`** |
| `max_tokens` | 可选 | **必填** |
| 出参结构 | `choices[0].message.content`（字符串） | **`content[0].text`（数组中取）** |
| `finish_reason` 值 | `stop/length/tool_calls` | `end_turn/max_tokens/stop_sequence/tool_use` |
| token 字段名 | `prompt_tokens/completion_tokens` | `input_tokens/output_tokens` |
| 流式结束标志 | `data: [DONE]` | **`event: message_stop`（无 DONE）** |
| 流式事件类型 | 单一 chunk 格式 | **多种事件类型（start/delta/stop）** |
| tools 参数字段 | `parameters` | **`input_schema`** |

---

## 八、错误码处理

### 错误响应体格式

```json
{
  "type": "error",
  "error": {
    "type": "authentication_error",
    "message": "invalid x-api-key"
  }
}
```

> ⚠️ **非标准 OpenAI 格式**：错误体顶层有 `type: "error"` 标识，错误码在 `error.type`（字符串类型），而非 `error.code`（整数）。

### HTTP 状态码 → 系统错误码映射

| HTTP 状态码 | 系统错误码 | 说明 |
|------------|-----------|------|
| 401 | `LLM_AUTH_FAILED`(2002009) | `x-api-key` 无效或格式错误 |
| 400 / 422 | `PARAM_ILLEGAL`(2001001) | 请求参数非法（如缺少必填的 `max_tokens`） |
| 429 | `LLM_RATE_LIMIT`(2002011) | 调用频率超限 |
| 529 | `LLM_RATE_LIMIT`(2002011) | 服务过载（Overloaded），与限速同等处理 |
| 其他 4xx/5xx | `LLM_CALL_FAILED`(2002001) | 平台调用失败（兜底） |

### Anthropic 常见 `error.type` 值

| `error.type` | 说明 |
|--------------|------|
| `authentication_error` | 认证失败，检查 x-api-key |
| `invalid_request_error` | 参数非法 |
| `rate_limit_error` | 频率超限 |
| `overloaded_error` | 服务过载（HTTP 529） |
| `api_error` | 平台内部错误 |

> 流式接口遇到 HTTP 错误时推送 `[ERROR:{httpCode}]`；同步接口抛 `BizException`，错误信息格式为 `[type] message`（如 `[authentication_error] invalid x-api-key`）。

