# 03 - 业务域说明

> 本项目为 Matrix 客户端，业务围绕「会话/房间/消息」展开。以下按功能域梳理，每个域列出功能、入口（feature 模块）与关键数据。功能入口的完整 Node/Presenter 清单见 `04-api-overview.md`。

## 核心概念（Matrix 领域）

- **Room（房间）**：包含有序 Event 的容器，由 `room_id` 标识。Space 也是 Room（不同 type）。
- **Event（事件）**：房间中的条目，含 `event_id`/`room_id`/`type`/`content`。State Event 记录房间状态（名称、成员等），普通 Event 承载消息内容。
- **Sync（同步）**：客户端与 homeserver 的数据同步，由 Rust SDK 管理。
- **Homeserver**：Matrix 服务端（参考实现 Synapse），客户端通过 Client-Server API 通信。

## 功能域清单

| 域 | 功能 | 入口模块 | 关键数据 |
| :--- | :--- | :--- | :--- |
| 登录/认证 | 登录、OIDC、二维码登录、账号提供方选择、FTUE 引导、退出登录、注销 | `login`、`ftue`、`logout`、`deactivation` | 会话凭据、认证状态 |
| 会话验证/加密 | 会话验证、密钥备份、安全与隐私 | `verifysession`、`securebackup`、`securityandprivacy` | 加密密钥、备份状态 |
| 房间/消息 | 时间线、消息收发、回复、编辑、反应、线程、固定消息、附件、语音、投票 | `messages`、`poll`、`forward`、`share` | Room、Event、TimelineItem |
| 房间管理 | 房间详情、编辑、成员/角色/权限、报告、离开、目录、别名解析 | `roomdetails`、`roomdetailsedit`、`roommembermoderation`、`rolesandpermissions`、`reportroom`、`leaveroom`、`roomdirectory`、`roomaliasresolver` | Room 状态、成员、权限 |
| 创建/加入 | 创建房间、加入房间、按地址加入、开始聊天、邀请、knock 请求 | `createroom`、`joinroom`、`startchat`、`invite`、`invitepeople`、`knockrequests` | Room、邀请、别名 |
| Space | 空间浏览、设置、添加房间、离开空间 | `space` | Space（Room type） |
| 用户/成员 | 用户资料、编辑资料、封禁用户、用户搜索 | `userprofile`、`preferences`（editprofile）、`usersearch` | User、成员信息 |
| 通知/推送 | 通知设置、推送历史、Firebase/UnifiedPush | `push`（库）、`preferences`（notifications） | Push 状态、通知偏好 |
| 设置 | 偏好、开发者选项、实验室、缓存清理、网络监控 | `preferences`、`cachecleaner`、`networkmonitor` | 偏好、缓存 |
| 位置 | 分享位置、查看位置 | `location` | 地理坐标、MapLibre |
| 通话 | Element Call、房间通话 | `call`、`roomcall` | 通话信令（Widget） |
| 锁屏 | PIN/生物识别解锁 | `lockscreen` | 安全凭据 |
| 反馈 | Rageshake 崩溃报告 | `rageshake` | 日志、截图 |
| 其它 | 许可证、公告、文件/文件夹查看、内容扫描、链接新设备、迁移 | `licenses`、`announcement`、`viewfolder`、`contentscanner`、`linknewdevice`、`migration`、`signedout`、`analytics` | 各类辅助数据 |

## 关键数据流（域视角）

- **会话生命周期**：登录 → `session-storage` 持久化凭据 → 后续启动恢复会话 → 登出/注销清理。
- **消息流**：Rust SDK 同步房间事件 → `libraries/matrix` 映射为 `TimelineItem` → Presenter 订阅 → Compose 渲染时间线。
- **推送**：后台收到 Push → 通知 SDK 加载 Event → 触发 sync。
