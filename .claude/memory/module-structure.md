---
name: module-structure
description: 所有 Gradle 模块的清单、职责与依赖关系（api/impl/test 三模块结构）
metadata:
  type: module-structure
---

# 模块结构

本项目是**多 Gradle 模块**项目。特性模块遵循 **api / impl / test** 三模块结构。

## 顶层模块

| 模块 | 职责 |
| :--- | :--- |
| `app` | Android 应用模块（`MainActivity`、Application、DI 装配、导航接线） |
| `appnav` | 根导航（`RootFlowNode`），组合各 feature 的 FlowNode |
| `appconfig` | 应用配置 |
| `appicon:element` / `appicon:enterprise` | 应用图标（Element / Enterprise 变体） |
| `annotations` / `codegen` | 注解与 KSP 代码生成 |
| `plugins` | 自定义 Gradle 插件（约定插件） |
| `tests/*` | `detekt-rules`、`konsist`、`uitests`、`testutils` |
| `enterprise` | 企业版专属功能模块 |

## `features/`（45 个，屏幕/流程）

`analytics`、`announcement`、`cachecleaner`、`call`、`contentscanner`、`createroom`、`deactivation`、`enterprise`、`forward`、`ftue`、`home`、`invite`、`invitepeople`、`joinroom`、`knockrequests`、`leaveroom`、`licenses`、`linknewdevice`、`location`、`lockscreen`、`login`、`logout`、`messages`、`migration`、`networkmonitor`、`poll`、`preferences`、`rageshake`、`reportroom`、`rolesandpermissions`、`roomaliasresolver`、`roomcall`、`roomdetails`、`roomdetailsedit`、`roomdirectory`、`roommembermoderation`、`securebackup`、`securityandprivacy`、`share`、`signedout`、`space`、`startchat`、`userprofile`、`verifysession`、`viewfolder`

- 每个 feature 通常拆分为 `api`（对外接口/数据类）、`impl`（Node/Presenter/View）、`test`（Fake/工具）。
- 大多数 feature **不感知其它 feature**，导航接线在 `app` 模块完成。

## `libraries/`（50 个，可复用库）

核心：`matrix`（Rust SDK 封装）、`designsystem`+`compound`（设计系统）、`architecture`（Node/Presenter 基类）、`session-storage`（会话存储）、`encrypted-db`（SQLCipher）、`preferences`（DataStore）、`push`+`pushstore`+`pushproviders`（推送）。

其它：`accountselect`、`androidutils`、`audio`、`cachestore`、`core`、`cryptography`、`dateformatter`、`deeplink`、`di`、`emoji`、`eventformatter`、`featureflag`、`fullscreenintent`、`indicator`、`matrixmedia`、`matrixui`、`mediapickers`、`mediaplayer`、`mediaupload`、`mediaviewer`、`network`、`oauth`、`permissions`、`previewutils`、`qrcode`、`roomselect`、`rustls-tls`、`rustsdk`、`slashcommands`、`testtags`、`textcomposer`、`troubleshoot`、`ui-common`、`ui-strings`、`ui-utils`、`usersearch`、`voiceplayer`、`voicerecorder`、`wellknown`、`workmanager`

## `services/`（5 个，跨功能服务）

| 服务 | 结构 | 职责 |
| :--- | :--- | :--- |
| `analytics` | api/impl/noop/compose/test | 分析事件 |
| `analyticsproviders` | api/sentry/posthog/test | 分析后端 |
| `apperror` | api/impl/test | 错误处理 |
| `appnavstate` | api/impl/test | 导航状态 |
| `toolbox` | api/impl/test | 开发工具 |

## 依赖关系要点

- `app` → `appnav` → feature `api`；`app` → feature `impl`（装配）。
- feature `impl` → 自身 `api` + `matrix:api` + 各 `libraries:*:api` + `architecture`。
- `matrix:impl` → `matrix:api`；`matrix:*` → Rust SDK。
- 模块在 `settings.gradle.kts` 通过 `includeProjects` 自动纳入（无需手动声明）。

详细模块依赖图见 `.claude/context/01-architecture.md`；各模块文件数统计见 `.claude/context/02-backend-modules.md`。
