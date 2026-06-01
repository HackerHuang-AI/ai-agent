# 智谱 GLM OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://open.bigmodel.cn/dev/api

---

## 一、认证方式

### Token 获取
1. 登录 https://open.bigmodel.cn
2. 进入 **API Keys** 页面获取 API Key（格式：`<id>.<secret>`，点分隔）

### JWT 生成方式（智谱特有）

智谱使用 **本地自签 JWT** 作为 Bearer Token，**不是直接用 API Key**。

**JWT 构造规则：**
- **Header**：`{"alg": "HS256", "sign_type": "SIGN"}`
- **Payload**：
  ```json
  {
    "api_key": "<API Key 中点之前的 id 部分>",
    "exp": 1748736000000,
    "timestamp": 1748735940000
  }
  ```
  - `timestamp`：当前时间毫秒数
  - `exp`：过期时间毫秒数，建议设为 `timestamp + 30分钟`（1800000ms）
- **签名密钥**：API Key 中点之后的 `secret` 部分，使用 HMAC-SHA256

**Java 生成示例（依赖 java-jwt）：**
```java
String[] parts = apiKey.split("\\.");
String id = parts[0];
String secret = parts[1];
long now = System.currentTimeMillis();

Algorithm algorithm = Algorithm.HMAC256(secret);
String token = JWT.create()
    .withKeyId(id)
    .withPayload(Map.of(
        "api_key", id,
        "timestamp", now,
        "exp", now + 30 * 60 * 1000L
    ))
    .withHeader(Map.of("alg", "HS256", "sign_type", "SIGN"))
    .sign(algorithm);
```

### 请求 Header

```http
Authorization: Bearer <JWT_TOKEN>
Content-Type: application/json
```

---

## 二、Endpoint

| 接口 | 方法 | URL |
|------|------|-----|
| 对话补全（同步 + 流式） | POST | `https://open.bigmodel.cn/api/paas/v4/chat/completions` |
| 模型列表 | GET | `https://open.bigmodel.cn/api/paas/v4/models` |

---

## 三、核心入参

```json
{
  "model": "glm-4-air",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.95,
  "max_tokens": 4096,
  "top_p": 0.7,
  "tools": [],
  "tool_choice": "auto",
  "request_id": "unique-request-id-001"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 模型标识，如 `glm-4-air`、`glm-4v`、`glm-5` |
| `messages` | array | ✅ | 与 OpenAI 格式一致 |
| `stream` | boolean | ❌ | 是否流式，默认 `false` |
| `temperature` | float | ❌ | 范围 `[0, 1]`，默认 `0.95`（上限 1，非 2） |
| `max_tokens` | integer | ❌ | 最大输出 token |
| `top_p` | float | ❌ | 范围 `(0, 1)`，默认 `0.7` |
| `tools` | array | ❌ | Function Call，格式与 OpenAI 一致 |
| `request_id` | string | ❌ | **智谱特有**：自定义请求 ID，用于问题排查 |
| `user_id` | string | ❌ | **智谱特有**：终端用户 ID，用于滥用监控 |

---

## 四、核心出参（同步）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "glm-4-air",
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
| `choices[0].finish_reason` | `stop` / `length` / `tool_calls` / `network_error` |
| `usage.prompt_tokens` | 输入 token 数 |
| `usage.completion_tokens` | 输出 token 数 |

---

## 五、核心出参（流式 SSE）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"glm-4-air","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"glm-4-air","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"glm-4-air","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":10,"total_tokens":30}}

data: [DONE]
```

| 字段 | 说明 |
|------|------|
| `choices[0].delta.content` | 当前 chunk 文本 |
| `usage`（最后一帧） | 智谱在 `finish_reason` 非空的帧中附带 `usage` |
| `data: [DONE]` | 流结束标志 |

---

## 六、主流模型列表

| 模型标识 | 上下文窗口 | 视觉 | Tool Call | 说明 |
|----------|-----------|------|-----------|------|
| `glm-4-air` | 128K | ❌ | ✅ | 主力模型，高性价比 |
| `glm-4-airx` | 128K | ❌ | ✅ | 极速版 |
| `glm-4-flash` | 128K | ❌ | ✅ | 免费模型 |
| `glm-4v` | 2K | ✅ | ✅ | 视觉理解 |
| `glm-4v-plus` | 8K | ✅ | ✅ | 视觉增强版 |
| `glm-4.6` | 128K | ❌ | ✅ | 新一代主力 |
| `glm-4.6v` | 128K | ✅ | ✅ | 新一代视觉 |
| `glm-4.7` | 128K | ❌ | ✅ | 新一代增强 |
| `glm-5` | 128K | ❌ | ✅ | 旗舰模型 |
| `glm-5.1` | 128K | ❌ | ✅ | 旗舰增强 |

---

## 七、与 OpenAI 的差异点

| 差异项 | OpenAI | 智谱 GLM |
|--------|--------|---------|
| 认证方式 | 直接用 API Key 作为 Bearer | **需要用 API Key 本地生成 JWT**，JWT 有过期时间 |
| `temperature` 范围 | `[0, 2]` | `[0, 1]`（上限不同） |
| `top_p` 默认值 | `1.0` | `0.7` |
| `request_id` | 不支持 | **支持**，便于排查 |
| `user_id` | 不支持（有 `user` 字段） | **支持** `user_id` |
| 流式 `usage` | 需额外参数开启 | 在最后一帧自动返回 |
| `finish_reason` 值 | `stop/length/tool_calls/content_filter` | 额外有 `network_error` |

