# 阿里云灵积 Token Plan OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-28 | 官方文档: https://help.aliyun.com/zh/model-studio/developer-reference/token-plan

---

## 一、认证方式

### Token 获取
1. 登录 https://bailian.console.aliyun.com
2. 进入「Token Plan 管理」页面，购买 Token 包后获取专属 API Key（格式：`sk-...`）

### 请求 Header

```http
Authorization: Bearer sk-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

> ⚠️ **Token Plan Key 与普通 DashScope Key 不同**：需在「Token Plan」专属页面创建，两者相互独立，不可混用。

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions` |

> 与标准 DashScope 接口（`dashscope.aliyuncs.com/compatible-mode/v1`）地址不同，专用于 Token Plan 计费模式。

---

## 三、核心入参

```json
{
  "model": "qwen3.7-max",
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
  "enable_search": false
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识，Token Plan 支持的模型见下表 |
| `messages` | array | ✅ | 与 OpenAI 格式一致，支持 `system` / `user` / `assistant` |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 2]`，默认 `0.7` |
| `max_tokens` | integer | ❌ | 最大输出 token 数，默认因模型而异 |
| `top_p` | float | ❌ | 范围 `(0, 1)`，默认 `0.9` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |
| `enable_search` | boolean | ❌ | **Qwen 系列特有**：是否开启联网搜索，默认 `false` |
| `stream_options` | object | ❌ | `{"include_usage":true}` 可在流式中返回 usage |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-xxxxxxxxxxxx",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "qwen3.7-max",
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
| `usage.total_tokens` | 本次消耗 Token 数（从 Token 包扣减） |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"qwen3.7-max","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"qwen3.7-max","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"qwen3.7-max","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}

data: [DONE]
```

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | Tool Call | 说明 |
|----------|-----------|-----------|------|
| `qwen3.7-max` | 128K | ✅ | Qwen3 旗舰版 MoE |
| `qwen3.7-plus` | 128K | ✅ | Qwen3 Plus 版本 |
| `qwen3.6-plus` | 128K | ✅ | Qwen3.6 Plus |
| `qwen3.6-flash` | 128K | ✅ | Qwen3.6 极速版 |
| `deepseek-v4-pro` | 64K | ✅ | Token Plan 托管 DeepSeek V4 Pro |
| `deepseek-v4-flash` | 64K | ✅ | Token Plan 托管 DeepSeek V4 Flash |
| `kimi-k2.7-code` | 128K | ✅ | Token Plan 托管 Kimi K2.7 Code |
| `kimi-k2.6` | 128K | ✅ | Token Plan 托管 Kimi K2.6 |
| `kimi-k2.5` | 128K | ✅ | Token Plan 托管 Kimi K2.5 |
| `glm-5.2` | 128K | ✅ | Token Plan 托管 GLM-5.2 |
| `glm-5.1` | 128K | ✅ | Token Plan 托管 GLM-5.1 |
| `glm-5` | 128K | ✅ | Token Plan 托管 GLM-5 |
| `MiniMax-M2.5` | 256K | ✅ | Token Plan 托管 MiniMax M2.5 |

---

## 七、与标准 DashScope 接口的差异

| 差异项 | DashScope 标准接口 | DashScope Token Plan |
|--------|-----------------|---------------------|
| Endpoint | `dashscope.aliyuncs.com/compatible-mode/v1` | `token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` |
| 计费模式 | 按量计费（实时扣费） | **包量预购**（预购 Token 包，更低单价） |
| API Key | 标准 DashScope Key | **独立的 Token Plan Key** |
| 模型范围 | 阿里自有 Qwen 系列 | Qwen + **聚合第三方**（DeepSeek、Kimi、GLM、MiniMax） |
| 适用场景 | 弹性调用、小批量 | 高频稳定、大批量、降本优化 |

## 八、与 OpenAI 的差异点

| 差异项 | OpenAI | 阿里云灵积 Token Plan |
|--------|--------|---------------------|
| Endpoint | `api.openai.com/v1` | `token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` |
| 协议 | 原生 OpenAI | **OpenAI 兼容** |
| `enable_search` | 不存在 | **特有**（Qwen 系列），开启联网搜索 |
| 计费模式 | 按量 | 预购包量 |

