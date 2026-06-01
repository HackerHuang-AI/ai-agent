# Ollama OpenAPI 文档

> 版本: v1 | 更新时间: 2026-06-01 | 官方文档: https://github.com/ollama/ollama/blob/main/docs/api.md

---

## 一、认证方式

### 本地部署默认无鉴权

Ollama 默认运行在本地，无需认证：

```http
Content-Type: application/json
```

### 可选：开启 API Key 认证

如需鉴权（如暴露为公网服务），启动时设置环境变量：
```bash
OLLAMA_API_KEY=your_key ollama serve
```

启用后请求 Header 需加：
```http
Authorization: Bearer your_key
```

---

## 二、Endpoint

Ollama 提供两套 API：**原生 API** 和 **OpenAI 兼容 API**（v0.1.24+ 支持）。

| 接口 | 方法 | URL（默认端口 11434） |
|------|------|---------------------|
| **原生** 对话补全（流式） | POST | `http://localhost:11434/api/chat` |
| **OpenAI 兼容** 对话补全 | POST | `http://localhost:11434/v1/chat/completions` |
| 模型列表 | GET | `http://localhost:11434/api/tags` |
| 拉取模型 | POST | `http://localhost:11434/api/pull` |
| 删除模型 | DELETE | `http://localhost:11434/api/delete` |
| 模型信息 | POST | `http://localhost:11434/api/show` |
| 服务健康检查 | GET | `http://localhost:11434/` |

> ✅ **推荐使用 OpenAI 兼容接口**（`/v1/chat/completions`），与其他平台保持一致，方便统一适配。

---

## 三、核心入参

### 3.1 OpenAI 兼容接口（推荐）

```json
{
  "model": "llama3.2",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user",   "content": "Hello!"}
  ],
  "stream": false,
  "temperature": 0.8,
  "max_tokens": 4096,
  "top_p": 0.9
}
```

与 OpenAI 格式完全一致，Ollama 会将其转发给原生接口处理。

### 3.2 原生 `/api/chat` 接口

```json
{
  "model": "llama3.2",
  "messages": [
    {"role": "user", "content": "Hello!"}
  ],
  "stream": false,
  "options": {
    "temperature": 0.8,
    "num_predict": 4096,
    "top_p": 0.9,
    "top_k": 40,
    "repeat_penalty": 1.1,
    "num_ctx": 4096,
    "seed": 42
  },
  "keep_alive": "5m"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `model` | string | ✅ | 本地已拉取的模型名，如 `llama3.2`、`qwen2.5` |
| `messages` | array | ✅ | 与 OpenAI 格式一致 |
| `stream` | boolean | ❌ | 是否流式，默认 `true`（注意：原生接口默认流式） |
| `options` | object | ❌ | **Ollama 特有**：模型推理参数集合 |
| `options.temperature` | float | ❌ | 随机性，范围 `[0, 1]` |
| `options.num_predict` | integer | ❌ | **等价于 `max_tokens`**，最大生成 token 数，`-1` 表示不限制 |
| `options.top_p` | float | ❌ | nucleus sampling |
| `options.top_k` | integer | ❌ | top-k 采样 |
| `options.repeat_penalty` | float | ❌ | 重复惩罚 |
| `options.num_ctx` | integer | ❌ | 上下文窗口大小（token 数），默认 `2048` |
| `options.seed` | integer | ❌ | 随机种子，固定值可复现输出 |
| `keep_alive` | string | ❌ | **Ollama 特有**：模型在内存中保留时间，默认 `"5m"`；`"0"` 立即卸载；`"-1"` 永久保留 |

---

## 四、核心出参（同步）

### 4.1 OpenAI 兼容接口出参（与 OpenAI 完全一致）

```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1748736000,
  "model": "llama3.2",
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

### 4.2 原生接口出参（非流式）

```json
{
  "model": "llama3.2",
  "created_at": "2026-06-01T10:00:00Z",
  "message": {
    "role": "assistant",
    "content": "Hello! How can I help you?"
  },
  "done": true,
  "done_reason": "stop",
  "total_duration": 1234567890,
  "load_duration": 56789000,
  "prompt_eval_count": 20,
  "prompt_eval_duration": 123456789,
  "eval_count": 10,
  "eval_duration": 987654321
}
```

| 字段 | 说明 |
|------|------|
| `message.content` | 模型回复内容 |
| `done` | 是否完成，非流式时固定为 `true` |
| `done_reason` | 结束原因：`stop` / `length` |
| `total_duration` | **Ollama 特有**：总耗时（纳秒） |
| `load_duration` | **Ollama 特有**：模型加载耗时（纳秒） |
| `prompt_eval_count` | 等价于 `prompt_tokens` |
| `eval_count` | 等价于 `completion_tokens` |

---

## 五、核心出参（流式）

### 5.1 原生接口流式（默认行为，JSON Lines 格式，非 SSE）

```
{"model":"llama3.2","created_at":"2026-06-01T10:00:00Z","message":{"role":"assistant","content":"Hello"},"done":false}
{"model":"llama3.2","created_at":"2026-06-01T10:00:00Z","message":{"role":"assistant","content":"!"},"done":false}
{"model":"llama3.2","created_at":"2026-06-01T10:00:00Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","eval_count":10,"eval_duration":987654321}
```

> ⚠️ 原生接口流式格式为 **JSON Lines**（每行一个完整 JSON），**不是 SSE**（无 `data:` 前缀，无 `[DONE]` 结束标志）。

### 5.2 OpenAI 兼容接口流式（SSE 格式，与 OpenAI 一致）

```
data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"llama3.2","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"llama3.2","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":null}]}

data: {"id":"chatcmpl-abc","object":"chat.completion.chunk","model":"llama3.2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

---

## 六、模型管理接口

### 拉取模型
```http
POST http://localhost:11434/api/pull
Content-Type: application/json

{"model": "llama3.2"}
```

### 查看已安装模型列表
```http
GET http://localhost:11434/api/tags
```

响应：
```json
{
  "models": [
    {
      "name": "llama3.2:latest",
      "model": "llama3.2:latest",
      "size": 2019393189,
      "modified_at": "2026-06-01T10:00:00Z"
    }
  ]
}
```

### 删除模型
```http
DELETE http://localhost:11434/api/delete
Content-Type: application/json

{"model": "llama3.2"}
```

---

## 七、常用本地模型

| 模型标识 | 参数量 | 大小 | 说明 |
|----------|--------|------|------|
| `llama3.2` | 3B | ~2GB | Meta Llama 3.2，轻量快速 |
| `llama3.2:1b` | 1B | ~1.3GB | 极轻量版 |
| `llama3.1:8b` | 8B | ~4.7GB | 主流开源模型 |
| `llama3.1:70b` | 70B | ~40GB | 大参数，需高显存 |
| `qwen2.5:7b` | 7B | ~4.4GB | 阿里 Qwen2.5 本地版 |
| `qwen2.5:14b` | 14B | ~9GB | Qwen2.5 中等版 |
| `deepseek-r1:7b` | 7B | ~4.7GB | Deepseek R1 推理模型本地版 |
| `mistral:7b` | 7B | ~4.1GB | Mistral 主流模型 |
| `nomic-embed-text` | — | ~274MB | 文本 Embedding 模型 |

---

## 八、与 OpenAI 的差异点

| 差异项 | OpenAI | Ollama |
|--------|--------|--------|
| 部署方式 | 云端 API | **本地部署**，无网络延迟 |
| 认证方式 | Bearer API Key（必须） | **默认无鉴权**，可选开启 |
| 原生流式格式 | SSE（`data: ...`） | **JSON Lines**（逐行 JSON，无 `data:` 前缀） |
| OpenAI 兼容接口 | 原生 | `/v1/chat/completions` 兼容 SSE 格式 |
| `max_tokens` 字段 | `max_tokens` | 原生接口为 `options.num_predict` |
| 推理参数 | 顶层字段 | **包裹在 `options` 对象中** |
| `keep_alive` | 不存在 | **特有**，控制模型在内存中的保留时间 |
| 模型管理接口 | 无（通过平台管理） | 有 `pull/delete/show` 等本地管理接口 |
| `num_ctx` | 不暴露 | **可控制**上下文窗口大小 |
| 计费 | 按 token 计费 | **免费**，本地算力 |

