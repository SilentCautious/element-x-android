---
name: tech-stack
description: 核心技术栈：语言、框架、数据库、中间件、UI 与构建工具
metadata:
  type: tech-stack
---

# 技术栈

## 语言与构建

| 层 | 技术 | 版本/备注 |
| :--- | :--- | :--- |
| 语言 | Kotlin | 2.4.10 |
| 构建 | Gradle + Android Gradle Plugin | AGP 9.3.1 |
| 依赖管理 | Gradle version catalog | `gradle/libs.versions.toml`，Renovate 自动升级 |
| 代码生成 | KSP + KotlinPoet | `codegen` 模块 |

## 架构与 UI

| 层 | 技术 | 说明 |
| :--- | :--- | :--- |
| UI | Jetpack Compose + Material3 | BOM 2026.08.00 |
| 设计系统 | Compound | `libraries/compound` + `libraries/designsystem` |
| 导航 | Appyx | 1.7.1（模型驱动导航，Node 树） |
| 状态管理 | Molecule + Compose runtime | Presenter 即 `@Composable` |
| 依赖注入 | Metro | 1.4.2（`@ContributesBinding`/`@ContributesNode`） |
| 状态机 | FlowRedux | `com.freeletics.flowredux` |

## 数据与网络

| 层 | 技术 | 说明 |
| :--- | :--- | :--- |
| 核心 SDK | Matrix Rust SDK | `org.matrix.rustcomponents:sdk-android`（Uniffi FFI） |
| 本地数据库 | SQLDelight + SQLCipher | `encrypted-db` 提供加密驱动 |
| 偏好存储 | AndroidX DataStore | `libraries/preferences` |
| HTTP | Retrofit + OkHttp | 主要用于 .well-known/发现 |
| 序列化 | kotlinx-serialization | JSON |
| 并发 | Kotlin Coroutines | 1.11.0 |
| 图片加载 | Coil 3 | 5.0.0 |

## 功能集成

| 能力 | 技术 |
| :--- | :--- |
| 推送 | Firebase (FCM) / UnifiedPush |
| 分析 | Sentry / PostHog / matrix-analytics-events |
| 地图 | MapLibre |
| 通话 | Element Call（embedded widget） |
| OIDC | `oauth` + `wellknown` |
| TLS | rustls + rustls-platform-verifier |

## 测试与质量

| 类别 | 技术 |
| :--- | :--- |
| 单元测试 | JUnit4 + Turbine + Molecule + Robolectric |
| UI 截图 | Showkase + Paparazzi / Roborazzi |
| 端到端 | Maestro |
| 覆盖率 | Kover |
| 静态检查 | Detekt + Ktlint + Konsist |

## 关键约定

- 依赖版本集中在 `gradle/libs.versions.toml`，新增依赖需在此声明。
- 升级 Rust SDK 只修 API break；升级 Appyx 需检查状态恢复；升级 rustls 需运行 `tools/sdk/update-rustls`。
