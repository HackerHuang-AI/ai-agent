# 百度千帆 OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-28 | 官方文档: https://cloud.baidu.com/doc/WENXINWORKSHOP/s/flfmc9do2

---

## 一、认证方式

### Token 获取
1. 登录 https://console.bce.baidu.com/qianfan
2. 进入「API Key 管理」页面创建 API Key（格式：`bce-v3/...`，或较新的 Bearer 格式）

> ⚠️ 千帆平台有两套鉴权体系：
> - **旧版**：`client_id` + `client_secret` 换取 `access_token`，通过 URL 参数传递
> - **新版（推荐）**：直接使用 API Key，通过 `Authorization: Bearer` 传递，兼容 OpenAI 协议

### 请求 Header（新版 API Key 鉴权）

```http
Authorization: Bearer bce-v3/ALTAK-xxxxxxxxxxxxxxxx
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（OpenAI 兼容，同步 + 流式） | POST | `https://qianfan.baidubce.com/v2/chat/completions` |
| 模型列表 | GET | `https://qianfan.baidubce.com/v2/models` |

---

## 三、核心入参

```json
{
  "model": "ernie-4.5-8k",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.8,
  "max_tokens": 4096,
  "top_p": 0.8,
  "tools": [],
  "tool_choice": "auto"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识，如 `ernie-4.5-8k`、`ernie-4.5-turbo-8k` |
| `messages` | array | ✅ | 与 OpenAI 格式一致，支持 `system` / `user` / `assistant` |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 1]`，默认 `0.8`（**注意上限是 1，与 OpenAI 不同**） |
| `max_tokens` | integer | ❌ | 最大输出 token 数，默认因模型而异 |
| `top_p` | float | ❌ | 范围 `(0, 1)`，默认 `0.8` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |
| `tool_choice` | string / object | ❌ | `auto` / `none` / 指定工具对象 |

---

## 四、核心出参（同步）

```json
{
  "id": "as-xxxxxxxxxxxx",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "ernie-4.5-8k",
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
data: {"id":"as-xxx","object":"chat.completion.chunk","model":"ernie-4.5-8k","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"as-xxx","object":"chat.completion.chunk","model":"ernie-4.5-8k","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"as-xxx","object":"chat.completion.chunk","model":"ernie-4.5-8k","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `usage`（最后一帧） | 在结束帧中携带 token 用量统计 |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | Tool Call | 说明 |
|----------|-----------|-----------|------|
| `ernie-5.1` | 128K | ✅ | ERNIE 5 旗舰版 |
| `ernie-5.0` | 128K | ✅ | ERNIE 5 标准版 |
| `ernie-x1.1` | 32K | ✅ | 深度推理模型 |
| `ernie-x1-turbo-32k-preview` | 32K | ✅ | 推理模型快速版 |
| `deepseek-v4-pro` | 64K | ✅ | 千帆托管 DeepSeek V4 Pro |
| `deepseek-v4-flash` | 64K | ✅ | 千帆托管 DeepSeek V4 Flash |
| `glm-5.2` | 128K | ✅ | 千帆托管 GLM-5.2 |
| `glm-5.1` | 128K | ✅ | 千帆托管 GLM-5.1 |
| `glm-5` | 128K | ✅ | 千帆托管 GLM-5 |
| `kimi-k2.6` | 128K | ✅ | 千帆托管 Kimi K2.6 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | 百度千帆 |
|--------|--------|---------|
| Endpoint | `api.openai.com/v1` | `qianfan.baidubce.com/v2` |
| `temperature` 范围 | `[0, 2]` | **`[0, 1]`**（超出会报错） |
| `temperature` 默认值 | `1.0` | `0.8` |
| `top_p` 默认值 | `1.0` | `0.8` |
| API Key 格式 | `sk-...` | `bce-v3/ALTAK-...` |
| 模型数量 | 多 | 自有 ERNIE 系列 + 聚合第三方模型 |

