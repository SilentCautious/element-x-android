# 设计：贴纸发送（Sticker Picker + 用户自定义贴纸包）

日期：2026-09-11
状态：已批准（方案：Composer 附件菜单入口 + 贴纸选择 BottomSheet + global account data 自定义包，Phase 1 不依赖 Rust SDK 上游改动）

## 背景与目标

Element X Android 已能**渲染** `m.sticker` 事件（`TimelineItemStickerView.kt`），但无法**发送**贴纸。Element 桌面/Web 端通过 Scalar 集成管理器的 `m.stickerpicker` user widget（iframe）实现发送，该方案在 Android 上不可行（见下）。本设计为 Element X Android 提供原生贴纸发送能力：

- Composer 附件菜单新增 "Sticker" 入口，打开贴纸选择器（BottomSheet）。
- 选择器展示用户自定义贴纸包中的贴纸，点击即发送 `m.sticker` 事件。
- 用户从设备相册导入图片到自己的贴纸包（上传到 homeserver 得 mxc URI，存入 account data）。
- 存储格式与 MSC2545（Image Packs，unstable `im.ponies.*`）兼容，为 Phase 2 房间包互操作铺路。

### 已确认的决策

- **不做 Scalar widget 方案**：Rust SDK widget machine 的 `FromWidgetRequest` 枚举不含 `m.sticker` action，iframe widget 无法向宿主发贴纸事件；需上游 PR 才可行，放弃。
- **Phase 1 数据源仅用户自定义包**（global account data）：FFI 无读取 room state event 的 API，无法读房间状态贴纸包（`im.ponies.room_emotes` / `m.room.image_pack`）；Phase 2 依赖上游。
- **发送用 `Room.sendRaw("m.sticker", json)`**：FFI `Timeline.send` 只接受 `RoomMessageEventContentWithoutRelation`，不含 sticker 变体。已知限制：无本地 echo、不走 send queue；加密房间中透明加密（其他主流客户端可解密显示）。
- **入口隐藏条件**：线程时间线（`isInThreadTimeline`）不显示贴纸入口；feature flag `StickerPicker`（isInLabs = true）控制整体可见性。
- **不做"最近使用贴纸"**（v1 范围外，Phase 2 可选）。
- **不追踪 analytics `Composer` 事件**：`Composer.MessageType` 枚举（LocationPin/LocationUser/Poll/Text/VoiceMessage）无 Sticker 变体，不新增上游依赖。

### MSC2545 存储格式（2026-09 最终版要点）

- 贴纸包 = room state event `m.room.image_pack`（unstable：`im.ponies.room_emotes`），`content.pack.usage: ["sticker"]`，`content.images: Map<shortcode, ImageObject>`。
- ImageObject：`url`（必填，mxc）、`body`（可选）、`info`（可选，`m.sticker` 的 ImageInfo）。
- **2026-02 MSC 提交移除了账户数据直接存包的 `m.image_packs` 事件**：账户数据 `m.image_pack.rooms`（unstable：`im.ponies.emote_rooms`）现在仅作为"引用房间包"的机制（room ID + state_key → 全局可用）。
- 历史生态中个人包存于 user account data `im.ponies.user_emotes`（带 state_key 的 global account data），Element Web 的 Stickerpicker widget 读写该类型。

**Phase 1 落地选择**：用户自定义包写入 **global account data `im.ponies.user_emotes`**（沿用历史生态类型，MSC2545 最终版的 `m.image_packs` 已移除、无标准个人包类型）。Android 端只读写这一份 JSON，不与其他客户端竞争写入（widget 管理时另说）。发送的 `m.sticker` content 遵循 spec：`{"body": ..., "url": "mxc://...", "info": {...}}`。

## 架构

### 模块布局

新建 `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/`：

| 文件 | 职责 |
| --- | --- |
| `StickerPickerBottomSheet.kt` | BottomSheet UI（包内贴纸网格 + 导入按钮 + 空态） |
| `StickerPickerState.kt` | 不可变 UI 状态 |
| `StickerPickerEvent.kt` | sealed interface 事件（SelectSticker / ImportSticker / Dismiss） |
| `StickerPickerPresenter.kt` | Molecule Presenter；加载包、导入图片（上传→入包→持久化）、发送 |
| `StickerPickerStatePreviewParam.kt` | Preview 参数 |

矩阵层改动：

| 文件 | 改动 |
| --- | --- |
| `libraries/matrix/api/.../room/JoinedRoom.kt` | 新增 `suspend fun sendSticker(url: String, body: String, info: ImageInfo?): Result<Unit>` |
| `libraries/matrix/impl/.../room/JoinedRustRoom.kt` | `innerRoom.sendRaw("m.sticker", json)` 实现 |
| `libraries/matrix/test/.../room/FakeJoinedRoom.kt` | fake + 记录调用参数 |
| `libraries/matrix/api/.../media/MediaUploadResponse` 等上传链路复用既有 `MatrixClient.uploadMedia` | 无改动 |

Composer/接线改动（`features/messages/impl/.../messagecomposer/` 与 `MessagesView.kt` 等）：

| 文件 | 改动 |
| --- | --- |
| `MessageComposerEvent.kt` | `PickAttachmentSource` 新增 `data object Sticker` |
| `MessageComposerState.kt` | 新增 `canSendSticker: Boolean` |
| `MessageComposerPresenter.kt` | 处理 `Sticker` 事件（关菜单）+ flag → `canSendSticker` |
| `AttachmentsBottomSheet.kt` | 新增 "Sticker" ListItem（`canSendSticker` 时显示）+ `onSendStickerClick` 回调 |
| `MessagesView.kt` / `MessagesNode.kt` | 传递 `onSendStickerClick`，渲染 `StickerPickerBottomSheet` |
| `MessagesPresenter.kt` | 注入/创建 `StickerPickerPresenter`（或独立 Presenter @Inject，见 DI） |
| `features/messages/impl/build.gradle.kts` | **无需改动**（依赖已齐：matrix.api/impl 经传递、mediapickers.api、matrixui） |

字符串：`features/messages/impl/src/main/res/values/temporary.xml`（新建，参照 room-message-search 计划 Task 4；模块当前只有 localazy.xml）。

### DI

- `StickerPickerPresenter` 用 `@Inject` 构造注入：`JoinedRoom`（RoomScope）、`MatrixClient`（SessionScope，account data + uploadMedia）、`PickerProvider`（相册选择器注册）、`CoroutineDispatchers`。无需 `@Assisted`（无 navigator 参数；发送结果以 snackbar 呈现则注入 `SnackbarDispatcher`）。
- 模式参照 `CustomReactionPresenter`（`@Inject class ... : Presenter<State>`，由 `MessagesPresenter` 聚合 present()）。

## 数据流

```
AttachmentsBottomSheet "Sticker" 项 → PickAttachmentSource.Sticker → 关闭菜单
  → View 层 onSendStickerClick() → StickerPickerBottomSheet 显示
StickerPickerPresenter.present():
  LaunchedEffect(Unit): room.sessionId 对应 client.getAccountData("im.ponies.user_emotes")
    → 解析 JSON → stickers 列表（AsyncData）
  SelectSticker → room.sendSticker(url, body, info) → 关闭 sheet（失败 → AsyncAction.Failure + snackbar）
  ImportSticker → galleryImagePicker.launch() → onResult(uri)
    → 读取文件 bytes + 解析尺寸/mime（BitmapFactory）→ client.uploadMedia(mime, bytes)
    → 包 JSON images[shortcode] += ImageObject → client.setAccountData("im.ponies.user_emotes", json)
    → 刷新本地列表
```

关键点：

- **包模型**（`StickerPickerPresenter` 内私有或 `sticker/` 包内文件）：
  ```kotlin
  @Immutable data class UserStickerPack(
      val displayName: String?,
      val stickers: ImmutableList<StickerImage>,   // StickerImage(shortcode, url, body?, info?)
  )
  ```
  JSON 序列化用 `kotlinx.serialization`（模块已传递依赖，见 mediaupload 等 impl 用法；若未在 classpath，grep 确认后用 `org.json` 手工解析——决策留给实施时，倾向 kotlinx.serialization 的 `JsonObject` 手动构建以避免定义完整 schema）。
- **shortcode 生成**：导入时以文件名（去扩展名、过滤 `[a-zA-Z0-9-_]`、截断 100 字节）为候选，冲突时追加 `-1`、`-2`。
- **尺寸解析**：`BitmapFactory.Options(inJustDecodeBounds = true)` 得 width/height/mime；发送的 `info` 带 `w/h/mimetype/size`（spec 的 m.sticker ImageInfo）。
- **多设备一致性**：`getAccountData` 每次打开 sheet 时拉取一次（不做 observe——FFI `observeAccountDataEvent` 仅支持固定枚举类型，无 Custom 变体）；导入写入用"读取-合并-写回"，冲突风险低（单用户单端操作）。
- **加密房间提示**（可选，v1 不做）：MSC2545 指出包内媒体未加密；发送的 m.sticker 事件本身在加密房间会透明加密（sendRaw 行为），可接受。

### 发送实现（矩阵层）

```kotlin
// JoinedRoom.kt
suspend fun sendSticker(url: String, body: String, info: ImageInfo?): Result<Unit>

// JoinedRustRoom.kt
override suspend fun sendSticker(url: String, body: String, info: ImageInfo?): Result<Unit> = withContext(roomDispatcher) {
    runCatchingExceptions {
        val json = buildJsonObject {
            put("body", body)
            put("url", url)
            info?.let { put("info", buildJsonObject {
                put("w", it.width); put("h", it.height)
                it.mimeType?.let { m -> put("mimetype", m) }
                it.size?.let { s -> put("size", s) }
            }) }
        }
        innerRoom.sendRaw(eventType = "m.sticker", content = json.toString())
    }
}
```

（`ImageInfo` 为既有 `libraries/matrix/api/.../timeline/item/event/ImageInfo.kt` 类型；若字段名不同以实际为准。发送路径与 `sendLiveLocation` 同款 `runCatchingExceptions + roomDispatcher` 模式。）

## UI 结构

```
StickerPickerBottomSheet（ModalBottomSheet，参照 AttachmentsBottomSheet/CustomReactionBottomSheet）
  标题行:  "Stickers" | 导入按钮（CompoundIcons.Add）
  网格:    LazyVerticalGrid(4 列) of 贴纸（AsyncImage 或 media 链路既有组件）
           单项: MediaSource → 图片（128dp 内保持比例），点击发送
  空态:    IconTitleSubtitleMolecule（"No stickers yet" + "Import images from your gallery"）
  导入中:  网格末尾 placeholder（LinearProgressIndicator）或按钮 loading 态
  失败:    上传/发送失败 → SnackbarDispatcher（通用错误文案）
```

- 图片加载复用时间线媒体渲染链路（`MediaRequestData` + `AsyncImage` 模式；见 `TimelineItemStickerView.kt` 的 STICKER_SIZE_IN_DP=128 一致性——picker 内项也用 128dp 网格单元）。
- 发送成功即关闭 sheet；`m.sticker` 无本地 echo，事件经 sync 回来后出现在时间线（延迟取决于 sync 间隔，可接受）。
- 组件遵循 Compound 设计系统（`ElementTheme`、`CompoundIcons.Sticker()`——`CompoundIcons.kt:580` 已存在）。

## 错误处理与 PII

- `getAccountData` 失败/空/JSON 解析失败 → 视为空包（不报错；首次使用必然为空）。
- `uploadMedia` 失败 → `Timber.e` + snackbar 通用错误。
- `setAccountData` 失败 → 同上；本地列表回滚（不显示未持久化的贴纸）。
- `sendSticker` 失败 → 同上，sheet 保持打开。
- **永不日志贴纸 body/shortcode/url 内容**；仅可记 RoomId、错误类型。
- shortcode 过滤失败（如非 ASCII 文件名）→ fallback `sticker_<epochMillis>`。

## 字符串（temporary.xml，en-GB）

`screen_sticker_picker_*`：标题（"Stickers"）、导入按钮、空态标题/副标题；`a11y_sticker_picker_*`：贴纸项（"Send sticker %1$s"）、导入按钮；`common_`/`action_` 复用 `CommonStrings` 已有的 `common_error`。

## 测试

`StickerPickerPresenterTest`（Turbine + FakeJoinedRoom/FakeMatrixClient/FakePickerProvider）：

1. 初始加载：account data 空 → 空态；有包 → 列表。
2. SelectSticker → `room.sendSticker` 以正确参数调用；成功后 sheet 关闭（target state）。
3. sendSticker 返回失败 → 失败态/sheet 保持。
4. ImportSticker → picker.launch 调用；onResult(uri) → uploadMedia 以读到的 bytes 调用 → setAccountData 合并新贴纸（JSON 断言）。
5. shortcode 冲突 → 追加后缀。
6. 上传失败 → 不写 account data、不更新列表。

`JoinedRustRoom` 不单测（FFI 薄层，仓库惯例）；`FakeJoinedRoom.sendSticker` 记录参数供上层测试。Preview：`@PreviewsDayNight` 覆盖 空态/网格/导入中/失败。

矩阵层单测（`libraries/matrix/impl` 若有 room 层测试基建则加 sendRaw 断言；无则跳过——FFI 层不测）。

## 验收标准

1. flag `StickerPicker`（labs）关闭时附件菜单无 "Sticker" 项；开启时显示；线程时间线不显示。
2. 首次打开 picker 显示空态 + 导入按钮。
3. 相册选图 → 上传 → 贴纸出现在网格；重开 sheet 持久（account data）。
4. 点击贴纸 → 时间线出现 m.sticker 事件（本端 sync 回来渲染；Element Web/桌面可见）。
5. 发送失败显示 snackbar，sheet 不误关。
6. Presenter 单测全绿；`./gradlew lint`、ktlint 通过。

## Phase 2（依赖 Rust SDK 上游，仅记录不实施）

- 读房间状态贴纸包：需 FFI 暴露 room state event 读取（`m.room.image_pack`/`im.ponies.room_emotes`）→ 选择器合并展示房间包 + 个人包（MSC2545 优先级：个人引用包 → 房间包 → 空间包）。
- `m.image_pack.rooms` 引用管理（启用/禁用房间包全局可见）。
- 本地 echo / send queue：需上游 `Timeline.sendSticker`。
- Scalar widget `m.sticker` action：需 widget machine 扩展。
- 最近使用贴纸（account data，如 `io.element.recent_stickers`）。
