# Auralis 聆语

本地优先、模型可插拔的语音工作台。对标 [Soniox App](https://soniox.com/soniox-app)：Smart Scribe、Translator、会后纪要。App 只负责采集、编排和呈现；**转写 / 后处理 / 翻译** 三个环节全部 BYOK，数据不经过我们的服务器。

```
音频采集 → VAD 与分片 → STT Provider → 转写稿（临时词 + 定稿词）
                              ├→ 翻译 Provider → 双语视图
                              └→ 后处理 LLM → 纪要 / 行动项
                              └→ 本地存储
```

## 架构（PRD §6）

| 层 | 技术 | 目录 |
| --- | --- | --- |
| 共享内核 | Kotlin Multiplatform（当前 JVM target 可在 Linux 验证；iOS/Android source set 已预留） | `shared/` |
| Android UI | Jetpack Compose + 麦克风前台服务 + Keystore | `androidApp/` |
| iOS / macOS UI | SwiftUI + AVAudioEngine + Keychain | `iosApp/` |
| 验证入口 | JVM demo + 单元测试 | `jvmDemo/` · `shared/src/commonTest` |

共享：会话管线、Provider 适配器、数据模型、存储、导出、成本估算。  
平台原生：UI、音频采集、后台保活、安全存储。

## 已实现的 P0（M0 / M1 内核）

| ID | 需求 | 状态 |
| --- | --- | --- |
| REC-1/2/3/4 | 麦克风、后台录音、边录边写、断网只录音 | 平台层接口 + iOS AVAudioEngine + Android FGS；管线断线补传 |
| STT-1/2/3/4 | 临时词/定稿、语种、热词、时间戳 | `Segmenter` + 各 STT 适配器 |
| TR-1/2/3 | 并排译文、按定稿句翻译、上下文+术语表 | `TranslationOrchestrator` |
| PP-1/2 | 会后纪要模板 + 自定义模板 | `PostProcessor` + `BuiltInTemplates` |
| LIB-1/2/3 | 会话库、可编辑覆盖层、MD/TXT 导出 | `SessionRepository` + `TranscriptExporter` |
| KEY-1/2/3/4 | Keychain/Keystore、连通性测试、配置档、引导 | `SecureStore` + `Presets` + 引导页 |
| COST-1 | 会话用量记录与估算 | `CostEstimator` |
| 适配器 | Soniox · Grok STT · OpenAI 兼容非流式 · ElevenLabs · OpenAI 兼容流式 · OpenAI 兼容 LLM | `provider/` |

**能力协商**：每个 STT 适配器声明 `streaming / interim / timestamps / diarization / 热词 / 原生翻译`。UI 与管线按声明开启、降级或隐藏功能。

**Grok STT 语言**：官方列表含葡语 `pt`，**不含中文**。中英会议默认推荐 Soniox 或国内 OpenAI 兼容端点。见 `Presets.grokSttLanguageNote`。

## 本地运行（Linux 可验证内核）

```bash
./gradlew :shared:jvmTest
./gradlew :jvmDemo:run
```

演示配置不需要 API Key，会脚本化输出中英字幕、翻译和纪要。

## 打开客户端

- **Android**：安装 Android SDK，在 `local.properties` 写 `sdk.dir=`，然后 Android Studio 打开工程。`settings.gradle.kts` 检测到 SDK 后才会 include `:androidApp`。
- **iOS / macOS**：`brew install xcodegen && cd iosApp && xcodegen generate && open Auralis.xcodeproj`。将 `shared` 以 Kotlin/Native framework 链入后，把 `AppStore` 换成 `AuralisApp`。

## Provider 预置

| 槽位 | 预置 |
| --- | --- |
| STT | Soniox `wss://stt-rt.soniox.com/transcribe-websocket` · ElevenLabs Scribe v2 · Grok `wss://api.x.ai/v1/stt` · OpenAI 兼容流式 / 非流式 |
| LLM | Grok · Gemini（OpenAI 兼容端点）· OpenAI · DeepSeek · 硅基流动 · 自定义 Base URL |

配置项：API Key、Base URL、模型名、可选 Header、可选短期 Token 端点。

## 明确不做（V1）

Windows / Web / 团队协作 / TTS 播报 / 系统级 Voice Typing / 自研或代付模型。

## 隐私

无账号、无自有后端。默认无遥测。密钥不进数据库、日志、崩溃报告、导出文件。设置页明示：「你的数据会发送给你所选的 Provider」。
