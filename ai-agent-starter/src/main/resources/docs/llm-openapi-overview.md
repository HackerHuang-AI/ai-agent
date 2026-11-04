# LLM 平台 OpenAPI 协议对比总览

> 版本: v5 | 接入状态更新时间: 2026-11-03（已核对项目代码及 Claude / Gemini 相关官方资料，未重新核验其他厂商全部协议）

---

## 一、协议类型总览

| 平台 | 协议类型 | Chat Endpoint | 认证方式 | 流式支持 | 与 OpenAI 差异等级 |
|------|----------|---------------|----------|----------|--------------------|
| OpenAI | 原生 OpenAI | `https://api.openai.com/v1/chat/completions` | Bearer Token | SSE | 基准 |
| Deepseek | OpenAI 兼容 | `https://api.deepseek.com/chat/completions` | Bearer Token | SSE | ⭐ 低（多 `reasoning_content` 字段） |
| Qwen（阿里云百炼） | OpenAI 兼容 | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（`enable_search` 等扩展参数） |
| 阿里云灵积 Token Plan | OpenAI 兼容 | `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions` | Bearer Token | SSE | ⭐ 低（与 Qwen 协议一致，不同 Key 和 Endpoint） |
| 豆包（字节跳动） | OpenAI 兼容 | `https://ark.cn-beijing.volces.com/api/v3/chat/completions` | Bearer Token | SSE | ⭐⭐ 中（`model` 填 endpoint_id） |
| MiniMax | ChatCompletion v2（OpenAI 风格的自有协议） | `https://api.minimaxi.com/v1/text/chatcompletion_v2` | Bearer Token | SSE | ⭐⭐ 中（`name`、`base_resp` 等字段差异） |
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

## 网关接入状态说明

下表和各平台详情页中的“已接入”仅指本项目已经实现的能力；厂商官方能力不等同于网关能力。图文请求统一使用 `POST /api/llm/chat`，各适配器将 `IMAGE` 内容转换为厂商图像块格式；`IMAGE` 的值可为图片 URL 或 `data:image/...;base64,...` data URI，请求应选择对应厂商端点实际支持的视觉模型。`POST /api/llm/chat/stream` 同样支持流式图文对话。`FILE` 和 `VIDEO` 为预留内容类型，当前通用 Chat 适配器未实现支持，且网关不提供 multipart 文件上传接口。

此处 Responses API 特指 OpenAI Responses 及其兼容协议，与 Chat Completions / Messages 独立，统一入口为 `POST /api/llm/responses` 和 `POST /api/llm/responses/stream`，同时支持 Dubbo Facade 同步与流式调用。网关使用 OpenAI Responses API 兼容的固定 `input` 项结构，由适配器映射为厂商请求。当前已实现 **OpenAI、DeepSeek、豆包、Qwen、GLM、千帆、Moonshot、MiniMax、MiMo、TokenHub、Ollama 共 11 家**的同步与流式适配；**Anthropic / Gemini 的 OpenAI 兼容 Responses 未实现**，调用时明确返回 `LLM_PROTOCOL_NOT_SUPPORTED`，不再返回空 `output`。工具调用通过统一的 `tools`、`toolChoice`、`toolCalls` 字段处理，实际可用性取决于适配器、所选模型与端点。

Qwen、GLM、千帆、Moonshot、MiniMax、MiMo、TokenHub、Ollama 的 Responses 当前只开放文本类内容块、思考历史和函数工具调用，不支持图像或文件内容块，工具仅支持 `function`；Moonshot、MiniMax、MiMo、TokenHub、Ollama 仅开放无状态调用，拒绝 `previous_response_id`、`conversation`、`store=true`、`background=true`。`tool_choice` 按平台限制校验：Qwen 支持 `auto/none/required` 与指定函数（`required` 限单工具，指定函数映射为 `allowed_tools`）；TokenHub 支持 `auto/none/required` 与指定函数；GLM 支持 `auto/none`；千帆、Moonshot、MiniMax、MiMo、Ollama 仅支持 `auto`。Qwen 不支持后台调用。OpenAI / DeepSeek / 豆包可透传现有 DTO 表达的图像字段，但不代表完整文件、音频或视频输入已建设。统一入口与协议原生输入结构的多模态扩展方向已讨论，尚未实施。

Claude 当前通过原生 `POST /v1/messages` 调用；[Anthropic 官方 SDK API 索引](https://github.com/anthropics/anthropic-sdk-python/blob/main/api.md)未查到直接兼容 `/responses` 的接口，官方文档站本次受区域限制，不能据此断言厂商绝不支持。Gemini 官方已提供 [Interactions API](https://ai.google.dev/gemini-api/docs/interactions)，文档说明已于 2026 年 6 月正式可用，支持服务端续接、后台执行与多模态，但项目尚未实现其协议适配；[OpenAI 兼容说明](https://ai.google.dev/gemini-api/docs/openai)本次未查到 `/responses` 支持。因此“未实现”指当前项目的特定协议缺口，不表示两家模型缺少对应能力，也不表示无法接入。

以上是代码实现状态，本地回归不等于真实上游验收；凭证、端点和模型配置需按目标账号核实。完整能力矩阵与协议说明见 [项目定位：LLM 平台能力建设状态](project/project-positioning.md#四llm-平台能力建设状态)。

---

## 二、认证方式对比

| 平台 | Header 字段 | 值格式 | Token 获取方式 |
|------|-------------|--------|----------------|
| OpenAI | `Authorization` | `Bearer sk-xxx` | 控制台直接获取 API Key |
| Deepseek | `Authorization` | `Bearer sk-xxx` | 控制台直接获取 API Key |
| Qwen | `Authorization` | `Bearer sk-xxx` | 阿里云百炼控制台获取 API Key |
| 阿里云灵积 Token Plan | `Authorization` | `Bearer sk-xxx` | Token Plan 管理页面单独创建 Key |
| 豆包 | `Authorization` | `Bearer xxx` | 火山引擎控制台获取 API Key |
| MiniMax | `Authorization` | `Bearer xxx` | 控制台获取 API Key |
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

| 参数 | OpenAI | Deepseek | Qwen | DashScope-TokenPlan | 豆包 | MiniMax | 智谱 | Moonshot | 百度千帆 | TokenHub | HY-TokenPlan | MiMo | Ollama | Anthropic | Google |
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
- **MiniMax**：流式结束标志为 `data: [DONE]`，但中间帧有额外的 `usage` 字段

---

## 五、各平台详细文档索引

| 文档 | 说明 |
|------|------|
| [llm-openapi-openai.md](llm-openapi-openai.md) | OpenAI 原生协议（基准参考） |
| [llm-openapi-deepseek.md](llm-openapi-deepseek.md) | Deepseek，OpenAI 兼容，含 `reasoning_content` |
| [llm-openapi-qwen.md](llm-openapi-qwen.md) | Qwen 阿里云百炼，OpenAI 兼容，含 `enable_search` |
| [llm-openapi-doubao.md](llm-openapi-doubao.md) | 豆包字节跳动，model 填 endpoint_id |
| [llm-openapi-minimax.md](llm-openapi-minimax.md) | MiniMax ChatCompletion v2，OpenAI 风格自有协议 |
| [llm-openapi-glm.md](llm-openapi-glm.md) | 智谱 GLM，JWT 认证 |
| [llm-openapi-moonshot.md](llm-openapi-moonshot.md) | Moonshot Kimi，基本无差异 |
| [llm-openapi-baidu-qianfan.md](llm-openapi-baidu-qianfan.md) | 百度千帆，temperature 上限为 1，聚合第三方模型 |
| [llm-openapi-tencent-tokenhub.md](llm-openapi-tencent-tokenhub.md) | 腾讯 TokenHub，聚合平台（混元+DeepSeek+GLM+Kimi+MiniMax） |
| [llm-openapi-mimo.md](llm-openapi-mimo.md) | 小米 MiMo，推理模型含 `reasoning_content` |
| [llm-openapi-ollama.md](llm-openapi-ollama.md) | Ollama 本地部署，含管理接口 |
| [llm-openapi-anthropic.md](llm-openapi-anthropic.md) | Anthropic Claude，x-api-key 认证，协议差异最大 |
| [llm-openapi-google.md](llm-openapi-google.md) | Google Gemini，提供原生和 OpenAI 兼容双接口 |

