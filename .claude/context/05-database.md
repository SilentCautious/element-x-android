# 05 - 数据库概览

> 对应传统后端的「数据库」。本项目没有传统关系型数据库，本地持久化用 **SQLDelight + SQLCipher**（加密），简单偏好用 **DataStore**，核心数据（会话/同步/加密）由 **Rust SDK** 自行管理。
> 表清单（AUTO 区）由脚本自动扫描 `.sq` 文件生成。

## 数据源配置

| 存储 | 技术 | 说明 |
| :--- | :--- | :--- |
| 会话凭据 | SQLDelight + SQLCipher | `libraries/session-storage`，`SessionData` 表 |
| 键值缓存 | SQLDelight + SQLCipher | `libraries/cachestore`，`CacheData` 表 |
| 推送请求/历史 | SQLDelight + SQLCipher | `libraries/push`（impl），`PushRequest`/`PushHistory` 表 |
| 简单偏好 | AndroidX DataStore | `libraries/preferences` |
| 核心状态 | Rust SDK 内部 | 房间/消息/加密等，应用层不直接访问 |

- 加密驱动由 `libraries/encrypted-db` 提供（SQLCipher）。
- 依赖见 `gradle/libs.versions.toml`：`sqldelight`（2.3.2）、`sqlcipher`（4.18.0）、`datastore`（1.2.1）。
- **Schema 升级**：DB 版本取 `sqldelight/databases` 目录下文件名中的最大数字；升级需新建 `.sqm` 迁移文件并运行对应 Gradle task（如 `generateDebugSessionDatabaseSchema`、`generateDebugCacheDatabaseSchema`）。

## 核心表（人工标注）

### `SessionData`（`libraries/session-storage`）

会话凭据表，主键 `userId`。关键列：`deviceId`、`accessToken`、`refreshToken`、`homeserverUrl`、`oidcData`、`isTokenValid`、`passphrase`、`sessionPath`、`cachePath`、`position`、`lastUsageIndex`、`userDisplayName`、`userAvatarUrl`。

> 注意：`accessToken` 等为敏感数据，禁止在日志中输出。

### `CacheData`（`libraries/cachestore`）

简单键值缓存，主键 `key`，列 `value`、`updatedAt`。

### `PushRequest` / `PushHistory`（`libraries/push`）

推送请求队列与历史，`PushRequest` 复合主键 `(sessionId, eventId)`，含 `pushDate`、`providerInfo`、`status`、`retries`。

## 表清单（自动生成）

<!-- AUTO-START -->
| 模块 | 表名 | 定义文件 |
| :--- | :--- | :--- |
<!-- AUTO-END -->
