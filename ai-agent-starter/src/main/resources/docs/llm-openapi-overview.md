# LLM 平台 OpenAPI 协议对比总览

> 版本: v3 | 更新时间: 2026-06-28

---

## 一、协议类型总览

| 平台 | 协议类型 | Chat Endpoint | 认证方式 | 流式支持 | 与 OpenAI 差异等级 |
|------|----------|---------------|----------|----------|--------------------|
| OpenAI | 原生 OpenAI | `https://api.openai.com/v1/chat/completions` | Bearer Token | SSE | 基准 |
| Deepseek | OpenAI 兼容 | `https://api.deepseek.com/chat/completions` | Bearer Token | SSE | ⭐ 低（多 `reasoning_content` 字段） |
| Qwen（阿里云百炼） | OpenAI 兼容 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（`enable_search` 等扩展参数） |
| 阿里云灵积 Token Plan | OpenAI 兼容 | `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（与 Qwen 协议一致，不同 Key 和 Endpoint） |
| 豆包（字节跳动） | OpenAI 兼容 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | Bearer Token | SSE | ⭐⭐ 中（`model` 填 endpoint_id） |
| Minimax | 自有协议 | `https://api.minimaxi.com/v1/text/chatcompletion_v2` | Bearer Token | SSE | ⭐⭐⭐ 高（入参结构差异大） |
| 智谱 GLM | OpenAI 兼容 | `https://open.bigmodel.cn/api/paas/v4/chat/completions` | JWT（自签） | SSE | ⭐⭐ 中（JWT 生成方式特殊） |
| Moonshot（Kimi） | OpenAI 兼容 | `https://api.moonshot.cn/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（基本无差异） |
| 百度千帆 | OpenAI 兼容 | `https://qianfan.baidubce.com/v2/chat/completions` | Bearer Token | SSE | ⭐ 低（`temperature` 范围上限为 1） |
| 腾讯 TokenHub | OpenAI 兼容 | `https://tokenhub.tencentmaas.com/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（聚合平台，协议基本无差异） |
| 腾讯混元 Token Plan | OpenAI 兼容 | `https://api.lkeap.cloud.tencent.com/plan/v3/chat/completions` | Bearer Token | SSE | ⭐ 低（仅混元模型，不同 Key 和 Endpoint） |
| 小米 MiMo | OpenAI 兼容 | `https://api.xiaomimimo.com/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（推理模型含 `reasoning_content`） |
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
| 阿里云灵积 Token Plan | `Authorization` | `Bearer sk-xxx` | Token Plan 管理页面单独创建 Key |
| 豆包 | `Authorization` | `Bearer xxx` | 火山引擎控制台获取 API Key |
| Minimax | `Authorization` | `Bearer xxx` | 控制台获取 API Key |
| 智谱 GLM | `Authorization` | `Bearer <JWT>` | 用 API Key 本地生成 JWT（有效期可配置） |
| Moonshot | `Authorization` | `Bearer sk-xxx` | 控制台直接获取 API Key |
| 百度千帆 | `Authorization` | `Bearer bce-v3/ALTAK-xxx` | 千帆控制台获取 API Key，格式特殊 |
| 腾讯 TokenHub | `Authorization` | `Bearer sk-xxx` | 腾讯云 LKEAP 控制台获取 API Key |
| 腾讯混元 Token Plan | `Authorization` | `Bearer sk-xxx` | Token Plan 管理页面单独创建 Key |
| 小米 MiMo | `Authorization` | `Bearer sk-xxx` | MiMo 控制台获取 API Key |
| Ollama | 无 | — | 本地部署，默认无鉴权（可配置 `OLLAMA_API_KEY`） |
| Anthropic | `x-api-key` + `anthropic-version` | `sk-ant-xxx` | 控制台获取 API Key，**Header 字段名不同，且需版本 Header** |
| Google | `Authorization`（兼容）/ URL `?key=`（原生） | `AIza...` | Google AI Studio 获取 API Key，两种接口认证方式不同 |

---

## 三、核心入参差异对比

| 参数 | OpenAI | Deepseek | Qwen | DashScope-TokenPlan | 豆包 | Minimax | 智谱 | Moonshot | 百度千帆 | TokenHub | HY-TokenPlan | MiMo | Ollama | Anthropic | Google |
|------|--------|----------|------|---------------------|------|---------|------|----------|---------|---------|-------------|------|--------|-----------|--------|
| `model` | 模型名 | 模型名 | 模型名 | 模型名 | **endpoint_id** | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 | 模型名 |
| `messages` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（结构略不同） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（不含 system） | ✅（兼容）/ **`contents`**（原生） |
| `stream` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `temperature` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（**上限 1**） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（原生在 `generationConfig` 中） |
| `max_tokens` | ✅ | ✅ | ✅ | ✅ | ✅ | `tokens_to_generate` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | `num_predict` | ✅（**必填**） | `maxOutputTokens`（原生） |
| `top_p` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `tools` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅（`input_schema`） | ✅ |
| 平台特有扩展 | — | `reasoning_content` | `enable_search` | `enable_search` | — | `bot_setting` | — | — | — | — | — | `reasoning_content` | `keep_alive` / `options` | `system`（独立字段） | `systemInstruction` / `safetyRatings` |

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
| [llm-openapi-deepseek.md](llm-openapi-deepseek.md) | Deepseek，OpenAI 兼容，含 `reasoning_content` |
| [llm-openapi-qwen.md](llm-openapi-qwen.md) | Qwen 阿里云百炼，OpenAI 兼容，含 `enable_search` |
| [llm-openapi-dashscope-tokenplan.md](llm-openapi-dashscope-tokenplan.md) | 阿里云灵积 Token Plan，预购包量，聚合多家模型 |
| [llm-openapi-doubao.md](llm-openapi-doubao.md) | 豆包字节跳动，model 填 endpoint_id |
| [llm-openapi-minimax.md](llm-openapi-minimax.md) | Minimax，自有协议，差异最大 |
| [llm-openapi-zhipu.md](llm-openapi-zhipu.md) | 智谱 GLM，JWT 认证 |
| [llm-openapi-moonshot.md](llm-openapi-moonshot.md) | Moonshot Kimi，基本无差异 |
| [llm-openapi-baidu-qianfan.md](llm-openapi-baidu-qianfan.md) | 百度千帆，temperature 上限为 1，聚合第三方模型 |
| [llm-openapi-tencent-tokenhub.md](llm-openapi-tencent-tokenhub.md) | 腾讯 TokenHub，聚合平台（混元+DeepSeek+GLM+Kimi+MiniMax） |
| [llm-openapi-mimo.md](llm-openapi-mimo.md) | 小米 MiMo，推理模型含 `reasoning_content` |
| [llm-openapi-ollama.md](llm-openapi-ollama.md) | Ollama 本地部署，含管理接口 |
| [llm-openapi-anthropic.md](llm-openapi-anthropic.md) | Anthropic Claude，x-api-key 认证，协议差异最大 |
| [llm-openapi-google.md](llm-openapi-google.md) | Google Gemini，提供原生和 OpenAI 兼容双接口 |

