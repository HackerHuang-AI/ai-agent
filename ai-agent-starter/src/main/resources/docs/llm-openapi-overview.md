# LLM 平台 OpenAPI 协议对比总览

> 版本: v2 | 更新时间: 2026-06-02

---

## 一、协议类型总览

| 平台 | 协议类型 | Chat Endpoint | 认证方式 | 流式支持 | 与 OpenAI 差异等级 |
|------|----------|---------------|----------|----------|--------------------|
| OpenAI | 原生 OpenAI | `https://api.openai.com/v1/chat/completions` | Bearer Token | SSE | 基准 |
| Deepseek | OpenAI 兼容 | `https://api.deepseek.com/chat/completions` | Bearer Token | SSE | ⭐ 低（多 `reasoning_content` 字段） |
| Qwen（阿里云百炼） | OpenAI 兼容 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（`enable_search` 等扩展参数） |
| 豆包（字节跳动） | OpenAI 兼容 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | Bearer Token | SSE | ⭐⭐ 中（`model` 填 endpoint_id） |
| Minimax | 自有协议 | `https://api.minimax.chat/v1/text/chatcompletion_v2` | Bearer Token | SSE | ⭐⭐⭐ 高（入参结构差异大） |
| 智谱 GLM | OpenAI 兼容 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | JWT（自签） | SSE | ⭐⭐ 中（JWT 生成方式特殊） |
| Moonshot（Kimi） | OpenAI 兼容 | `https://api.moonshot.cn/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（基本无差异） |
| Ollama | OpenAI 兼容（本地） | `http://localhost:11434/api/chat` | 无（本地） | SSE / JSON stream | ⭐⭐ 中（本地部署，额外管理接口） |
| Anthropic（Claude） | 自有协议 | `https://api.anthropic.com/v1/messages` | x-api-key（非 Bearer） | SSE（多事件类型） | ⭐⭐⭐ 高（Header/结构/流式均不同） |
| Google（Gemini） | 自有 + OpenAI 兼容 | `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` | Bearer / URL 参数 | SSE / JSON 数组流 | ⭐⭐⭐ 高（原生协议结构差异大） |

---

## 二、认证方式对比

| 平台 | Header 字段 | 值格式 | Token 获取方式 |
|------|-------------|--------|----------------|
| OpenAI | `Authorization` | `Bearer sk-xxx` | 控制台直接获取 API Key |
| Deepseek | `Authorization` | `Bearer sk-xxx` | 控制台直接获取 API Key |
| Qwen | `Authorization` | `Bearer sk-xxx` | 阿里云百炼控制台获取 API Key |
| 豆包 | `Authorization` | `Bearer xxx` | 火山引擎控制台获取 API Key |
| Minimax | `Authorization` | `Bearer xxx` | 控制台获取 API Key |
| 智谱 GLM | `Authorization` | `Bearer <JWT>` | 用 API Key 本地生成 JWT（有效期可配置） |
| Moonshot | `Authorization` | `Bearer sk-xxx` | 控制台直接获取 API Key |
| Ollama | 无 | — | 本地部署，默认无鉴权（可配置 `OLLAMA_API_KEY`） |
| Anthropic | `x-api-key` + `anthropic-version` | `sk-ant-xxx` | 控制台获取 API Key，**Header 字段名不同，且需版本 Header** |
| Google | `Authorization`（兼容）/ URL `?key=`（原生） | `AIza...` | Google AI Studio 获取 API Key，两种接口认证方式不同 |

---

## 三、核心入参差异对比

| 参数 | OpenAI | Deepseek | Qwen | 豆包 | Minimax | 智谱 | Moonshot | Ollama | Anthropic | Google |
|------|--------|----------|------|------|---------|------|----------|--------|-----------|--------|
| `model` | 模型名 | 模型名 | 模型名 | **endpoint_id** | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 |
| `messages` | ✅ | ✅ | ✅ | ✅ | ✅（结构略不同） | ✅ | ✅ | ✅ | ✅（不含 system） | ✅（兼容）/ **`contents`**（原生） |
| `stream` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `temperature` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（原生在 `generationConfig` 中） |
| `max_tokens` | ✅ | ✅ | ✅ | ✅ | `tokens_to_generate` | ✅ | ✅ | `num_predict` | ✅（**必填**） | `maxOutputTokens`（原生） |
| `top_p` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `tools` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（`input_schema`） | ✅ |
| 平台特有扩展 | — | `reasoning_content` | `enable_search` | — | `bot_setting` | — | — | `keep_alive` / `options` | `system`（独立字段） | `systemInstruction` / `safetyRatings` |

---

## 四、流式响应格式对比

所有平台流式均基于 SSE（Server-Sent Events），格式基本一致：

```
data: {"choices":[{"delta":{"content":"..."},"finish_reason":null}]}
data: [DONE]
```

**差异点：**
- **Ollama**：流式默认返回 `{"message":{"content":"..."},"done":false}`，使用 `/api/chat` 接口而非 `/v1/chat/completions`（但 Ollama 也提供 OpenAI 兼容的 `/v1/chat/completions`）
- **Minimax**：流式结束标志为 `data: [DONE]`，但中间帧有额外的 `usage` 字段

---

## 五、各平台详细文档索引

| 文档 | 说明 |
|------|------|
| [llm-openapi-openai.md](llm-openapi-openai.md) | OpenAI 原生协议（基准参考） |
| [llm-openapi-deepseek.md](llm-openapi-deepseek.md) | Deepseek，OpenAI 兼容 |
| [llm-openapi-qwen.md](llm-openapi-qwen.md) | Qwen 阿里云百炼，OpenAI 兼容 |
| [llm-openapi-doubao.md](llm-openapi-doubao.md) | 豆包字节跳动，model 填 endpoint_id |
| [llm-openapi-minimax.md](llm-openapi-minimax.md) | Minimax，自有协议，差异最大 |
| [llm-openapi-zhipu.md](llm-openapi-zhipu.md) | 智谱 GLM，JWT 认证 |
| [llm-openapi-moonshot.md](llm-openapi-moonshot.md) | Moonshot Kimi，基本无差异 |
| [llm-openapi-ollama.md](llm-openapi-ollama.md) | Ollama 本地部署，含管理接口 |
| [llm-openapi-anthropic.md](llm-openapi-anthropic.md) | Anthropic Claude，x-api-key 认证，协议差异最大 |
| [llm-openapi-google.md](llm-openapi-google.md) | Google Gemini，提供原生和 OpenAI 兼容双接口 |

