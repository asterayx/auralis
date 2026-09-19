# PRD: 跨平台 BYOK 实时转写与翻译 App（对标 Soniox）

2026-09-19

实现代号：**Auralis / 聆语**。本文件是产品规格；代码对照见根目录 `README.md`。

## 一句话定位

一个本地优先、模型可插拔的语音工作台——App 只负责采集、编排和呈现，转写、后处理、翻译三个环节全部由用户自带的 API Key 驱动，数据不经过我们的服务器。

## 目标

- 在 iOS、macOS、Android 上提供一致的实时转写、实时翻译、会后后处理体验。
- 三个环节（STT / 后处理 LLM / 翻译）均通过 BYOK 调用，每个环节可独立配置 Provider。
- 无自有服务器：密钥、音频、文本全部留在用户设备。
- Provider 以适配器形式接入，新增一个厂商不改动核心代码。

## 非目标（V1）

- 不自研或托管任何模型，不提供代付/转售额度。
- 不做团队工作区、评论协作、Web 端。
- 不做 Windows 和 Web 客户端。
- 不做 TTS 语音播报译文。
- 系统级 Voice Typing 放 V2。

## 核心管线

音频采集 → VAD 与分片 → STT Provider → 转写稿（临时词 + 定稿词）→ 翻译 / 后处理 / 本地存储。

音频只送 STT；翻译和后处理只接收文本。三个槽位可以各用各的厂商。

## 里程碑映射

| 阶段 | 本仓库状态 |
| --- | --- |
| M0 技术验证 | 共享内核已跑通：麦克风 PCM → STT 适配器（含 Soniox / Grok / OpenAI 兼容）→ 字幕 → OpenAI 兼容翻译。JVM 测试 + `jvmDemo` 可在 Linux 验收。 |
| M1 MVP P0 | 内核与 Android / iOS UI 骨架已按 P0 实现。真机音频/后台需在带 SDK 的机器上联调。 |
| M2 Android + V1 P1 | 说话人分轨、中英双向翻译、原文/译文同步高亮已接到共享内核与 Android / iOS / Linux demo。 |
| M3 V2 | 未做（Voice Typing、TTS、本地模型）。 |

## 已核实的 Provider 事实（2026-09）

- Soniox：`wss://stt-rt.soniox.com/transcribe-websocket`，token 含 `is_final` / `speaker` / 原生翻译。
- Grok STT：`wss://api.x.ai/v1/stt` + `POST /v1/stt`。语言表含葡语，**不含中文**。
- ElevenLabs Scribe v2：`wss://api.elevenlabs.io/v1/speech-to-text/realtime`。
- LLM：统一 Chat Completions + Grok / Gemini / DeepSeek / 硅基流动预置。
