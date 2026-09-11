# 设计：房间内聊天记录搜索（按内容 + 按成员 + 按时间筛选）

日期：2026-09-11
状态：已批准（方案 A：`features/messages/impl` 内新建 `search/` 子包）

## 背景与目标

为房间聊天页面增加聊天记录检索能力：用户可在房间内按**内容**搜索消息，并用**成员**（发送者）与**时间**（日期范围）筛选结果；点击结果回到房间时间线并定位到该消息。

### 已确认的决策

- **覆盖范围**：仅本地 tantivy 索引（feature flag `MessageSearch` 控制），历史旧消息不索引、不可搜。
- **功能形态**：独立搜索页 + 筛选器（不做时间线内原生过滤——SDK `TimelineEventCondition` 无 sender 条件）。
- **按时间**：日期范围筛选（预设区间 + 自定义起止日期），不做 jump-to-date。
- **按成员**：底部成员选择器 ModalBottomSheet，可多选。
- **入口**：房间 TopBar 搜索图标，仅当 `MessageSearch` feature flag 开启时显示。
- **结果点击**：关闭搜索页，回房间时间线并定位该消息（复用 `viewInTimeline` / `FocusOnEvent` 链路）。

## 架构

### 模块布局

`features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/`：

| 文件 | 职责 |
| --- | --- |
| `RoomMessageSearchNode.kt` | Appyx Node（`@ContributesNode(RoomScope::class)`），持有 Presenter，接线导航回调 |
| `RoomMessageSearchPresenter.kt` | Molecule Presenter；创建搜索游标、查询防抖、结果映射、客户端过滤、自动补页 |
| `RoomMessageSearchState.kt` | 不可变 UI 状态 |
| `RoomMessageSearchEvent.kt` | sealed interface 事件 |
| `RoomMessageSearchView.kt` | 无状态 Composable |
| `RoomMessageSearchFilter.kt` | 筛选器模型（选中成员集合、日期范围） |
| `RoomMessageSearchStatePreviewParam.kt` | Preview 参数 |

测试：`features/messages/impl/src/test/kotlin/.../search/RoomMessageSearchPresenterTest.kt`。

### 导航接线

- `MessagesFlowNode.NavTarget` 新增 `@Parcelize data object RoomMessageSearch : NavTarget`。
- `MessagesNode.Callback` 新增 `navigateToRoomMessageSearch()`：TopBar 图标点击时由 `MessagesView` 回调到 `MessagesFlowNode`，后者执行 `backstack.push(NavTarget.RoomMessageSearch)`；`resolve()` 中创建 `RoomMessageSearchNode`（接线模式同 `PinnedMessagesList`）。
- TopBar 图标加在 `MessagesMenuActions`（`MessagesView.kt`），条件：`state.isRoomMessageSearchEnabled`（由 feature flag 驱动，经 `MessagesState` 下传）。
- 结果点击 → `viewInTimeline(eventId)`（现有链路，`pushToBackstack = false`）。

### DI

- Presenter 注入：`MessageSearchService`（SessionScope 已导出）、`BaseRoom`（RoomScope，`membersStateFlow` 提供成员列表）、`FeatureFlagService`、`DateFormatter`、`RoomLatestEventFormatter`、`PermalinkParser`、`FileSizeFormatter`、`CoroutineDispatchers`。
- Node 构造注入 `RoomMessageSearchPresenter.Factory`（`@AssistedFactory`，模式同 `PinnedMessagesListPresenter.Factory`）。

## 数据流

```
TopBar 图标 → push(RoomMessageSearch) → Node → Presenter.present()
  remember { messageSearchService.createMessageSearch(scope = coroutineScope, roomId = room.roomId) }
  LaunchedEffect(queryText): delay(200ms) → setQuery(escaped by SDK layer)
  combine(results, paginationState) → 客户端过滤（senderIds / dateRange）→ 映射为 UI 项 → state
  可见区触底或筛选后结果不足 → paginate()
```

关键点：

- **每次进入搜索页创建一个新游标**（`MessageSearchService` KDoc 契约：一屏一游标、绑定屏 scope；不能每击键一个）。
- `MessageSearchResult` 已含 `roomId/eventId/senderId/senderProfile/content/timestamp`，过滤在 Presenter 暴露边界完成。
- 结果映射复用 `GlobalSearchPresenter` 的 `mapMessageContent` / `mapMediaContent` 模式（文本/媒体两种 UI 项）。

### 筛选器（客户端过滤）

- **按成员**：`result.senderId in selectedSenderIds`。成员选择器数据来自 RoomScope 的 `room.membersStateFlow`（joinedRoomMembers），选择器内本地搜索复用 `BaseRoom.filterMembers` / `FilterRoomMembers` 模式。
- **按时间**：`result.timestamp in startMillis..endMillis`。预设：今天 / 7 天 / 30 天 / 全部；自定义用 Material3 DateRangePicker（起止日期，含当天整日）。
- 筛选条件以 chips 显示在搜索框下方，可单独移除；移除全部成员/时间条件即回到未筛选。
- 筛选条件变化不重新发起 SDK 查询，只对已加载结果重过滤 + 必要时自动补页。

### 自动补页策略（稀疏结果）

房间过滤本身是客户端侧（SDK 跨全部房间搜索后过滤），叠加成员/时间过滤后单页结果可能全被滤掉：

- 每次结果更新后：**过滤后可见结果数 < 10 且 `paginationState is Idle && !endReached` → 自动 `paginate()`**。
- 连续自动补页上限 10 次；达到上限后停止自动补页，显示已有结果 + 底部"加载更多"按钮。
- 筛选条件变化或新查询时重置计数。
- 无筛选时的常规触底分页沿用 GlobalSearchPresenter 模式（可见区末尾距结果尾部 < 10 提前量）。

### 已知限制（需在 UI 空态与文档中明示）

1. **必须输入查询文本**：tantivy 不支持空查询；成员/时间筛选只能作为结果的过滤器，不能单独作为查询条件（空查询时筛选器置灰不可用）。
2. **首条结果延迟**：SDK 全局搜索后按房间过滤，本房间结果稀疏时需多次分页才出现首条；Loading 态需覆盖整个自动补页周期。
3. **索引覆盖**：只索引开启 flag 后收到的消息；更早的消息不可搜。

## UI 结构

```
TopAppBar: 返回按钮 | 搜索框（自动聚焦）| 清除文本按钮
筛选行:   成员 chips（多个，可移除）+ "添加成员" chip；时间 chip（当前区间文案，可移除/点击改）
结果列表:  sender 头像 | sender 名 + 格式化时间（DateFormatterMode.TimeOrDate, relative）
          | 内容预览（文本/媒体两种，复用 GlobalSearchView 渲染模式）
状态:     AsyncData 三态；endReached 且无匹配 → "无匹配消息" 空态（含索引范围提示文案）
成员选择器: ModalBottomSheet + 成员列表 + 本地搜索
日期选择器: 预设 chips + DateRangePicker dialog
```

- 组件遵循 Compound 设计系统（`ElementTheme`、`CompoundIcons`）。
- 结果项不含房间名/房间头像（与全局搜索不同——此处已在房间上下文内）。

## 错误处理与 PII

- `setQuery` 失败 → `Timber.e`（不含查询文本内容）→ `AsyncData.Failure` 展示通用错误空态。
- **查询文本与消息体永不写日志**（遵循 `RustMessageSearch.kt` PII 先例）；仅可记结果数量、分页状态。
- 成员列表加载失败沿用 `RoomMembersState` 既有错误态。

## 字符串（temporary.xml，en-GB）

`screen_room_message_search_*`：标题、搜索框占位、空查询提示、无结果、筛选-成员/时间、添加成员、日期预设（今天/7天/30天/全部/自定义）、起止日期、加载更多、索引范围提示、错误文案；a11y 文案用 `a11y_` 前缀。

## 测试

`RoomMessageSearchPresenterTest`（Turbine + 既有 `FakeMessageSearch` / `FakeMessageSearchService`）覆盖：

1. 查询防抖后 `setQuery` 以最新文本调用
2. 成员过滤（单选/多选/移除）
3. 日期范围过滤（边界：起日 0 点、止日 24 点前）
4. 筛选后结果不足触发自动补页；达到上限后停止
5. 触底分页（无筛选）
6. 结果点击事件携带正确 eventId
7. 空查询时不出结果且筛选器不可用

Preview：`@PreviewsDayNight` 覆盖默认/加载/结果/空态/带筛选 chips/错误态。

## 验收标准

1. flag 开启时房间 TopBar 出现搜索图标，点击进入搜索页；flag 关闭时图标不显示。
2. 输入关键词后展示本房间匹配消息，触底自动分页。
3. 成员选择器多选后结果仅含所选发送者的消息；移除后恢复。
4. 日期范围筛选后结果仅含区间内消息；边界正确。
5. 点击结果返回时间线并定位到该消息。
6. 无匹配时显示空态与索引范围提示。
7. Presenter 单测全绿；`./gradlew lint`、ktlint 通过。
