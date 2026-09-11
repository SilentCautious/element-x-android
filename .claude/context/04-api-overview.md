# 04 - 功能入口清单（API 概览）

> 对应传统后端的「Controller 清单」。本项目没有 Controller，功能入口是 Appyx **Node**（导航/接线）与 **Presenter**（状态机）。
> 下表由 `scripts/gen-context.ps1`（或 `.sh`）自动扫描生成，人工标注请写在 AUTO 区之外。

## 概念映射

| 传统后端概念 | 本项目对应物 |
| :--- | :--- |
| Controller | Appyx `Node`（每个屏幕/流程的入口，负责导航与 DI） |
| Service / 业务逻辑 | `Presenter`（`@Composable` 状态机，`present(): State`） |
| 请求参数 | `Event`（UI 上行动作） |
| 响应体 | `State`（不可变 UI 状态） |

每个 feature 的 `Node`/`Presenter` 通常位于 `features/<name>/impl/src/main/kotlin/...`，对外入口在 `features/<name>/api`。

## 功能入口清单（自动生成）

<!-- AUTO-START -->
| 模块 | 类型 | 名称 | 文件 |
| :--- | :--- | :--- | :--- |
<!-- AUTO-END -->
