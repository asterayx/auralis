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
| LIB-1/2/3 | 会话库、LLM 自动标题、可编辑覆盖层、MD/TXT 导出与分享 | `JsonSessionRepository` + `SessionTitle` + `TranscriptExporter` |
| KEY-1/2/3/4 | Keychain/Keystore、Base URL/模型、连通性测试、配置档、引导 | `SecureStore` + `Presets` + 引导页 |
| COST-1 | 会话用量记录与估算，会话详情可见 | `CostEstimator` |
| STT-2/3 · TR-3 | 语种选择、热词、术语表设置页 | `AppSettings` |
| REC-3 | 可选保留本地音频；Android 边录边写 PCM | `AudioCapture` + 设置开关 |
| UI-M2 | 说话人分轨、中英双向翻译、原文/译文同步 | `SpeakerRoster` + `TranslationOrchestrator` + Live / Library UI |
| 适配器 | Soniox · Grok STT · OpenAI 兼容非流式 · ElevenLabs · OpenAI 兼容流式 · OpenAI 兼容 LLM | `provider/` |

**能力协商**：每个 STT 适配器声明 `streaming / interim / timestamps / diarization / 热词 / 原生翻译`。UI 与管线按声明开启、降级或隐藏功能。

**Grok STT 语言**：官方列表含葡语 `pt`，**不含中文**。中英会议默认推荐 Soniox 或国内 OpenAI 兼容端点。见 `Presets.grokSttLanguageNote`。

## 本地运行（Linux 可验证内核）

```bash
./gradlew :shared:jvmTest
./gradlew :jvmDemo:run
```

默认打开 http://127.0.0.1:43173 的演示页（说话人、双向翻译、同步高亮）。终端脚本：

```bash
./gradlew :jvmDemo:run --args="--cli"
```

演示配置不需要 API Key。

## 打开客户端

- **Android**：安装 Android SDK，在 `local.properties` 写 `sdk.dir=`，然后 Android Studio 打开工程。`settings.gradle.kts` 检测到 SDK 后才会 include `:androidApp`。
- **iOS / macOS**：`brew install xcodegen && cd iosApp && xcodegen generate && open Auralis.xcodeproj`。将 `shared` 以 Kotlin/Native framework 链入后，把 `AppStore` 换成 `AuralisApp`。

## 云端 CI 打 iOS 安装包（IPA）

这台 Cursor Cloud Agent 是 **Linux**，没有 Xcode，**不能在这里直接编出可装到 iPhone 的 IPA**。Apple 只允许在 macOS 上编译和签名。当前账号下也没有已连接的 Mac 自托管 Worker。

可以走云端 **macOS 编译机**：

| 方式 | 编译机 | 入口 |
| --- | --- | --- |
| GitHub Actions | `macos-15` + Xcode 16 | `.github/workflows/ios-ipa.yml`（手动 Run workflow） |
| Codemagic | 云端 Mac mini | `codemagic.yaml` |
| 自己的 Mac | 本机 Xcode | `bash iosApp/ci/build-ipa.sh` |

装到真机还需要 Apple Developer 签名，在仓库 Secrets 里放：

- `DEVELOPMENT_TEAM` — Team ID
- `BUILD_CERTIFICATE_BASE64` — 发布/开发证书 `.p12` 的 base64
- `P12_PASSWORD`
- `BUILD_PROVISION_PROFILE_BASE64` — `.mobileprovision` 的 base64

`IOS_EXPORT_METHOD`：`development`（插线） / `ad-hoc`（指定 UDID） / `app-store`（TestFlight 签名）。

### 直接推到 TestFlight

可以。TestFlight **只接受 App Store 签名的 IPA**，development / ad-hoc 包传不上去。

在 Actions 里勾选 `upload_testflight`，或本机：

```bash
export IOS_EXPORT_METHOD=app-store
bash iosApp/ci/build-ipa.sh
bash iosApp/ci/upload-testflight.sh
```

额外 Secrets（App Store Connect → 用户和访问 → 集成 → App Store Connect API）：

- `APP_STORE_CONNECT_KEY_ID`
- `APP_STORE_CONNECT_ISSUER_ID`
- `APP_STORE_CONNECT_API_KEY_P8`（`.p8` 原文或 base64）

还要先在 Apple Developer 建好 Bundle ID `com.auralis.app`，并在 App Store Connect 建同名 App。证书必须是 **Apple Distribution**，描述文件必须是 **App Store**。处理完一般 5–15 分钟后出现在 TestFlight。

```bash
gh workflow run "iOS IPA" --ref cursor/auralis-byok-scribe-4672 \
  -f export_method=app-store -f upload_testflight=true
```

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
