---
name: business-context
description: 业务背景与核心业务域清单（Matrix 客户端领域）
metadata:
  type: business-context
---

# 业务背景

Element X Android 是 **Matrix 协议**的官方客户端。Matrix 是一个开放的、去中心化的实时通信协议，采用联邦架构：任何客户端（如本应用）都能与任何 homeserver 通信。

## 核心业务域

1. **会话/身份（Session & Identity）**：登录、OIDC、二维码登录、多账号、退出/注销。会话凭据由应用持久化（`session-storage`），其余状态由 Rust SDK 管理。
2. **房间与消息（Room & Messaging）**：房间是核心容器（含有序 Event），Space 是特殊 Room。消息收发、回复/编辑/反应、线程、固定消息、附件、语音、投票。
3. **房间管理（Room Management）**：创建/加入房间、成员与角色权限、房间详情/编辑、报告、目录、别名。
4. **加密与安全（E2EE & Security）**：端到端加密、会话验证、密钥备份、锁屏（PIN/生物识别）。
5. **通知与推送（Notifications & Push）**：Firebase/UnifiedPush 推送、通知偏好、推送历史。
6. **通话（Calling）**：内嵌 Element Call 语音/视频通话。
7. **位置（Location）**：位置分享与查看（MapLibre）。
8. **分析与反馈（Analytics & Feedback）**：Sentry/PostHog 分析、Rageshake 崩溃反馈。

## 领域关键对象

| 对象 | 说明 |
| :--- | :--- |
| `Room` | 房间，由 `room_id` 标识；Space 也是 Room |
| `Event` | 房间条目，由 `event_id` + `type` + `state_key` 唯一标识 |
| `Timeline` / `TimelineItem` | 时间线（`libraries/matrix` 对 SDK 类型的映射） |
| `Session` | 会话（userId/deviceId/accessToken 等） |
| `User` / `RoomMember` | 用户与房间成员 |

## 业务规则要点

- 登录/消息等业务逻辑在 Rust SDK 与 `libraries/matrix` 层，UI 只通过 Presenter 订阅状态。
- 敏感数据（accessToken、passphrase、消息内容）**禁止记录日志**；Matrix ID（userId/roomId/eventId）可安全记录。
- 多语言默认 `en`，字符串经 Localazy 与 iOS 共享（禁改 `localazy.xml`）。

详细业务域说明见 `.claude/context/03-business-domains.md`。
