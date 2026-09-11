# 02 - 逻辑层 / 库模块详解

> 对应传统后端的「后端模块」。本项目没有独立后端，逻辑层由 `libraries/`（可复用库）、`services/`（跨功能服务）以及 `libraries/matrix`（Rust SDK 封装）承担。
> 稳定锚点见 `.claude/memory/module-structure.md`；本文件提供包结构、核心类与配置细节。

## 包结构

所有代码统一以 `io.element.android` 为前缀：

| 命名空间 | 目录 | 职责 |
| :--- | :--- | :--- |
| `io.element.android.x` | `app/` | 应用入口（MainActivity、Application） |
| `io.element.android.appnav` | `appnav/` | 根导航（RootFlowNode） |
| `io.element.android.features.*` | `features/<name>/` | 屏幕/功能（api/impl/test 三模块） |
| `io.element.android.libraries.*` | `libraries/<name>/` | 可复用库 |
| `io.element.android.services.*` | `services/<name>/` | 跨功能服务 |

## 核心库（逻辑层主力）

### `libraries/matrix` — Rust SDK 封装（最重要的库）

- `api` 模块：对 Rust SDK 类型的 Kotlin 映射（避免 `MatrixRustSDK` 泄漏进 UI）。
  关键子包（`io.element.android.libraries.matrix.api`）：
  `core`（MatrixClient、会话）、`room`（Room/RoomMember/timeline）、`auth`（登录/认证）、
  `encryption`、`notification(s)settings`、`media`、`user`、`sync`、`spaces`、`poll`、
  `roomlist`、`roomdirectory`、`search`、`permalink`、`verification`、`widget`、`oauth`、`tracing` 等。
- `impl` 模块：基于 `matrix-rust-components-kotlin` 生成类的具体实现。
- 命名映射约定：SDK `Room` → `JoinedRoom`/`RoomInfo`；`userId`（而非 `userID`）。

### `libraries/session-storage` — 会话凭据存储

- 负责持久化 userId、deviceId、accessToken、refreshToken、homeserverUrl 等（SQLDelight，见 `05-database.md`）。
- 应用侧只负责会话凭据存储，其余状态由 Rust SDK 管理。

### `libraries/designsystem` + `libraries/compound` — 设计系统

- 主题（`ElementTheme.colors`/`ElementTheme.typography`）、通用 Composables、Compound 组件与 token。
- UI 层一律优先使用 Compound 组件与 token。

### `libraries/architecture` — 架构基类

- Appyx 扩展、动画、overlay、coverage 规则。Node/Presenter 基类所在。

### 其它关键库

| 库 | 职责 |
| :--- | :--- |
| `cachestore` | 键值缓存（SQLDelight `CacheData` 表） |
| `push` / `pushstore` / `pushproviders` | 推送（Firebase / UnifiedPush） |
| `mediaupload` / `matrixmedia` / `mediaviewer` / `mediapickers` / `mediaplayer` | 媒体处理 |
| `cryptography` | 加密相关 |
| `oauth` / `wellknown` | OIDC / .well-known 发现 |
| `deeplink` | 深链接 |
| `preferences` | DataStore 偏好封装 |
| `encrypted-db` | SQLCipher 加密 SQLDelight 驱动 |
| `rustls-tls` | rustls 平台证书校验（NDK TrustManager） |
| `workmanager` | 后台任务（WorkManager） |
| `featureflag` | 功能开关 |
| `matrixui` / `textcomposer` / `ui-strings` / `ui-common` / `ui-utils` | UI 辅助 |

## `services/` — 跨功能服务

| 服务 | 结构 | 职责 |
| :--- | :--- | :--- |
| `analytics` | api/impl/noop/compose/test | 分析事件（noop 实现用于测试/无分析场景） |
| `analyticsproviders` | api/sentry/posthog/test | 分析后端（Sentry / PostHog） |
| `apperror` | api/impl/test | 全局错误处理 |
| `appnavstate` | api/impl/test | 导航状态 |
| `toolbox` | api/impl/test | 开发/调试工具集 |

## 配置文件

| 文件 | 作用 |
| :--- | :--- |
| `settings.gradle.kts` | 模块声明（`includeProjects` 自动包含 features/libraries/services/enterprise） |
| `build.gradle.kts`（根） | 根构建脚本 |
| `gradle/libs.versions.toml` | 依赖版本目录（所有依赖/插件集中声明） |
| `plugins/src/main/kotlin/*.gradle.kts` | 自定义 Gradle 插件（jvm/android/compose library 约定插件） |
| `gradle.properties` | Gradle 属性 |

## 各模块 Kotlin 文件数统计（自动生成）

<!-- AUTO-START -->
| 模块 | Kotlin 文件数 |
| :--- | ---: |
<!-- AUTO-END -->
