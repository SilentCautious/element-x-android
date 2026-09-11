# 06 - 前端（UI）结构

> 本项目是「前后端一体」的 Android 应用，UI 层即「前端」：Jetpack Compose + Compound 设计系统。
> 稳定锚点见 `.claude/memory/tech-stack.md`。

## UI 技术栈

- **Jetpack Compose**（BOM 2026.08.00）+ Material3。
- **Compound 设计系统**（`libraries/compound` + `libraries/designsystem`）：颜色、排版、图标、通用组件。
- 主题通过 `ElementTheme.colors.*` / `ElementTheme.typography.*` 访问；图标用 `CompoundIcons.Xxx()`。
- 单 Activity 全 Compose，无 XML 布局、无 Fragment。

## 目录结构

```
libraries/
  compound/        # Compound 组件与 token（颜色/排版/图标）
  designsystem/    # 主题 + 通用 Composable + Preview 辅助
  matrixui/        # Matrix 相关的 UI 组件
  textcomposer/    # 富文本/Markdown 输入
  ui-strings/      # 本地化字符串资源
  ui-common/       # 通用 UI 辅助
  ui-utils/        # UI 工具
features/*/impl/
  src/main/kotlin/io/element/android/features/<name>/.../*View.kt   # 各屏幕无状态 View
  .../*StatePreviewParam.kt                                         # Preview/截图测试样本
```

## 每个屏幕的 View 约定

| 文件 | 作用 |
| :--- | :--- |
| `FooView.kt` | 无状态 Composable，输入 `FooState`、回调 `FooEvent` |
| `FooState.kt` | 不可变 UI 状态 data class |
| `FooEvent.kt` | 密封接口（sealed interface）的 UI 动作 |
| `FooStatePreviewParam.kt` | 提供 Preview / 截图测试样本（`PreviewParameterProvider`） |

## Preview 与截图测试

- 为所有主要状态创建 Preview，用 `@PreviewsDayNight` 注解，包裹在 `ElementPreview { ... }`。
- 用 `PreviewParameterProvider`（如 `FooStatePreviewParam`）提供多状态。
- 截图测试用 Showkase + Paparazzi/Roborazzi；写测试时**不要录制截图**，由 CI 完成。
- 提交含 Preview 新增/变更的 PR 需加 `Record-Screenshots` 标签。

## 项目间差异（Flavor / 变体）

| 维度 | Element | Enterprise |
| :--- | :--- | :--- |
| 图标 | `appicon/element` | `appicon/enterprise` |
| minSdk | 24（Android 7.0） | 33（Android 13） |
| 企业功能 | — | `enterprise/` 模块 |
| 包名 | `io.element.android.x` | 企业变体 |
