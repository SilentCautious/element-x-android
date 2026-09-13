# 贴纸发送（Sticker Picker）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Element X Android 增加原生贴纸发送：Composer 附件菜单新增 "Sticker" 入口 → 贴纸选择 BottomSheet → 点击贴纸发送 `m.sticker` 事件；用户从相册导入图片到自己的贴纸包（上传得 mxc URI → 存 global account data `im.ponies.user_emotes`，MSC2545 兼容 JSON）。

**Architecture:** `JoinedRoom.sendSticker` API 落在矩阵层（impl 用 `innerRoom.sendRaw("m.sticker", json)`，无本地 echo/send queue——FFI 限制）；`features/messages/impl` 内新建 `sticker/` 子包（`@Inject` Presenter + BottomSheet View，模式参照 CustomReaction，经 `MessagesPresenter` 聚合）；Composer 附件菜单加入口（`PickAttachmentSource.Sticker` + `onSendStickerClick` View 层回调，模式同 Location/Poll）；feature flag `StickerPicker`（labs）控制；线程时间线隐藏入口。

**Tech Stack:** Jetpack Compose、Molecule、Metro DI、kotlinx.coroutines（Turbine 测试）、Truth 断言、kotlinx.serialization.json（`JsonObject` 手动构建——**若模块编译报 serialization 缺失，在 build.gradle.kts 加 `alias(libs.plugins.kotlin.serialization)` 插件与 `implementation(libs.serialization.json)`，参照 `features/call/impl/build.gradle.kts`**；matrix.api 的 `implementation` 级依赖不保证传递）。

**设计文档：** `docs/superpowers/specs/2026-09-11-sticker-picker-design.md`

---

## 约定（每个任务通用）

- 版权头：复制任意现有文件的 2026 版头（`Copyright (c) 2026 Element Creations Ltd.` + SPDX 两行）。
- 主源集前缀：`features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/`
- 测试源集前缀：`features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/`
- sticker 包名：`io.element.android.features.messages.impl.sticker`
- 代码不写注释（仓库规则），KDoc 除外（仅在必要契约处）。
- 硬换行 160 字符。
- 字符串资源：`features/messages/impl/src/main/res/values/temporary.xml`（**新建**，模块当前只有 localazy.xml）。
- **PII**：永不日志贴纸 body/shortcode/url/相册 URI；仅可记 RoomId 与错误类型。

## 可用构件速查（已验证存在）

| 构件 | 位置/签名 |
| --- | --- |
| `MatrixClient.getAccountData(eventType): Result<String?>` / `setAccountData(eventType, content): Result<Unit>` | `libraries/matrix/api/.../MatrixClient.kt:444,452`；Fake 构造参数 `getAccountDataLambda`/`setAccountDataLambda`（`FakeMatrixClient.kt:128-129`） |
| `MatrixClient.uploadMedia(mimeType, data): Result<String>`（返回 mxc URI） | Fake：`givenUploadMediaResult`（`FakeMatrixClient.kt:346`），默认成功返回 `AN_AVATAR_URL` |
| FFI `Room.sendRaw(eventType: String, content: String)` | AAR 反编译已验证；`JoinedRustRoom.innerRoom` 可调 |
| `ImageInfo(height: Long?, width: Long?, mimetype: String?, size: Long?, thumbnailInfo, thumbnailSource, blurhash)` | `libraries/matrix/api/.../media/ImageInfo.kt` |
| `MediaSource(url: String, json: String?)`（`.safeUrl`） | `libraries/matrix/api/.../media/MediaSource.kt` |
| `PickerProvider.registerGalleryPicker { uri, mimeType -> }` → `PickerLauncher.launch()` | `libraries/mediapickers/api/.../PickerProvider.kt:27`；使用先例 `MessageComposerPresenter.kt:195` |
| `FeatureFlags` 枚举项（`key`/`title`/`description`/`defaultValue`/`isFinished`） | `libraries/featureflag/api/.../FeatureFlags.kt:17`（MessageSearch 先例） |
| `FakeFeatureFlagService(initialState: Map<String, Boolean>)` | `libraries/featureflag/test/.../FakeFeatureFlagService.kt` |
| `CompoundIcons.Sticker()` | `libraries/compound/.../CompoundIcons.kt:580` |
| Presenter 聚合注入（`private val customReactionPresenter: Presenter<CustomReactionState>` + `present()`） | `MessagesPresenter.kt:115,167` |
| `MessageComposerState.isInThreadTimeline` | `MessageComposerState.kt:24` |
| 渲染参照 `TimelineItemStickerView.kt`（STICKER_SIZE_IN_DP=128，MediaRequestData 链路） | `.../timeline/components/event/` |
| `ModalBottomSheet` + `rememberBottomSheetState` | `AttachmentsBottomSheet.kt:74-79` |
| Turbine 工具 `test { }` / `testCoroutineDispatchers()` / `consumeItemsUntilPredicate` | `io.element.android.tests.testutils` |

**依赖确认**：`features/messages/impl/build.gradle.kts` 已含 `matrix.api`、`mediapickers.api`、`designsystem`、`coil.compose`、`featureflag.api`；test 源集已含 `matrix.test`、`featureflag.test`、`mediapickers.test`（L100）。

**注意**：`PickAttachmentSource` 是 sealed interface（`MessageComposerEvent.kt:24`），加 `data object Sticker` 后 `when` 需覆盖（非 exhaustive 编译错，正好强制处理）。`AttachmentsBottomSheet` 的 ListItem 用 `ListItemContent.Icon(IconSource.Vector(...))` + `Modifier.clickable`（见 `AttachmentsBottomSheet.kt:106-110`）。

---

### Task 1: 矩阵层 `sendSticker` API

**Files:**
- Modify: `libraries/matrix/api/src/main/kotlin/io/element/android/libraries/matrix/api/room/JoinedRoom.kt`
- Modify: `libraries/matrix/impl/src/main/kotlin/io/element/android/libraries/matrix/impl/room/JoinedRustRoom.kt`
- Modify: `libraries/matrix/test/src/main/kotlin/io/element/android/libraries/matrix/test/room/FakeJoinedRoom.kt`

- [ ] **Step 1.1: `JoinedRoom.kt` 新增 API（`sendLiveLocation` L318 旁）**

```kotlin
/**
 * Send a sticker event (`m.sticker`) to the room.
 *
 * @param url the mxc URI of the sticker image.
 * @param body a textual description of the sticker.
 * @param info optional image info (dimensions, mimetype, size).
 * @return a [Result] indicating whether the event was sent. Note: there is no local echo; the sticker
 *         appears in the timeline once it comes back through a sync.
 */
suspend fun sendSticker(url: String, body: String, info: ImageInfo?): Result<Unit>
```

（import `io.element.android.libraries.matrix.api.media.ImageInfo`。）

- [ ] **Step 1.2: `JoinedRustRoom.kt` 实现（`sendLiveLocation` L549 旁，同款 `withContext(roomDispatcher)` + `runCatchingExceptions` 模式）**

```kotlin
override suspend fun sendSticker(url: String, body: String, info: ImageInfo?): Result<Unit> = withContext(roomDispatcher) {
    runCatchingExceptions {
        val content = buildJsonObject {
            put("body", body)
            put("url", url)
            if (info != null) {
                put("info", buildJsonObject {
                    info.width?.let { put("w", it) }
                    info.height?.let { put("h", it) }
                    info.mimetype?.let { put("mimetype", it) }
                    info.size?.let { put("size", it) }
                })
            }
        }
        innerRoom.sendRaw(eventType = "m.sticker", content = content.toString())
    }
}
```

（import `kotlinx.serialization.json.buildJsonObject`、`kotlinx.serialization.json.put`；impl 模块已用 kotlinx-serialization——先 grep 确认版本 import 可用。）

- [ ] **Step 1.3: `FakeJoinedRoom.kt` 实现（记录参数供上层测试）**

```kotlin
val sentStickers = mutableListOf<Triple<String, String, ImageInfo?>>()
private var sendStickerResult: Result<Unit> = Result.success(Unit)

override suspend fun sendSticker(url: String, body: String, info: ImageInfo?): Result<Unit> {
    sentStickers.add(Triple(url, body, info))
    return sendStickerResult
}

fun givenSendStickerResult(result: Result<Unit>) {
    sendStickerResult = result
}
```

- [ ] **Step 1.4: 编译三个模块**

Run: `./gradlew :libraries:matrix:api:compileDebugKotlin :libraries:matrix:impl:compileDebugKotlin :libraries:matrix:test:compileDebugKotlin`
Expected: PASS

- [ ] **Step 1.5: Commit**

```
git add libraries/matrix
git commit -m "Add sendSticker to the room API

Sends m.sticker events through the SDK's sendRaw FFI, which encrypts
transparently in encrypted rooms. No local echo is available."
```

---

### Task 2: 贴纸包模型与 JSON 转换（纯逻辑，TDD）

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/StickerPack.kt`
- Test: `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/sticker/StickerPackTest.kt`

模型 + `im.ponies.user_emotes` 格式（`{"pack": {...}, "images": {shortcode: {url, body, info}}}`）双向转换。

- [ ] **Step 2.1: 写失败测试**

```kotlin
package io.element.android.features.messages.impl.sticker

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.ImageInfo
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class StickerPackTest {
    private val packJson = """
        {
          "pack": { "display_name": "My stickers", "usage": ["sticker"] },
          "images": {
            "cat": {
              "url": "mxc://example.org/cat",
              "body": "a cat",
              "info": { "w": 512, "h": 512, "mimetype": "image/png", "size": 12345 }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parse - null or blank account data yields empty pack`() {
        val empty = UserStickerPack(displayName = null, stickers = persistentListOf())
        assertThat(parseUserStickerPack(null)).isEqualTo(empty)
        assertThat(parseUserStickerPack("")).isEqualTo(empty)
        assertThat(parseUserStickerPack("  ")).isEqualTo(empty)
    }

    @Test
    fun `parse - malformed json yields empty pack`() {
        assertThat(parseUserStickerPack("not json").stickers).isEmpty()
    }

    @Test
    fun `parse - full pack maps all fields`() {
        val pack = parseUserStickerPack(packJson)
        assertThat(pack.displayName).isEqualTo("My stickers")
        assertThat(pack.stickers).hasSize(1)
        val sticker = pack.stickers.first()
        assertThat(sticker.shortcode).isEqualTo("cat")
        assertThat(sticker.url).isEqualTo("mxc://example.org/cat")
        assertThat(sticker.body).isEqualTo("a cat")
        assertThat(sticker.info).isEqualTo(
            ImageInfo(width = 512L, height = 512L, mimetype = "image/png", size = 12345L, thumbnailInfo = null, thumbnailSource = null, blurhash = null)
        )
    }

    @Test
    fun `parse - entries without url are skipped`() {
        val json = """{"images": {"bad": {"body": "no url"}, "good": {"url": "mxc://e.org/good"}}}"""
        val pack = parseUserStickerPack(json)
        assertThat(pack.stickers.map { it.shortcode }).containsExactly("good")
    }

    @Test
    fun `serialize - round trip preserves stickers`() {
        val pack = parseUserStickerPack(packJson)
        val reparsed = parseUserStickerPack(serializeUserStickerPack(pack))
        assertThat(reparsed.stickers).isEqualTo(pack.stickers)
        assertThat(reparsed.displayName).isEqualTo(pack.displayName)
    }

    @Test
    fun `serialize - pack usage is sticker`() {
        val json = serializeUserStickerPack(parseUserStickerPack(packJson))
        assertThat(json).contains("\"usage\"")
        assertThat(json).contains("\"sticker\"")
    }

    @Test
    fun `add - appends sticker and deduplicates shortcodes`() {
        val pack = parseUserStickerPack(packJson)
        val added = pack.addSticker(shortcode = "cat", url = "mxc://example.org/cat2", body = "another cat", info = null)
        assertThat(added.stickers.map { it.shortcode }).containsExactly("cat", "cat-1").inOrder()
        val addedAgain = added.addSticker(shortcode = "cat-1", url = "mxc://example.org/x", body = null, info = null)
        assertThat(addedAgain.stickers.map { it.shortcode }).containsExactly("cat", "cat-1", "cat-1-1").inOrder()
    }

    @Test
    fun `sanitize - enforces shortcode grammar with fallback`() {
        assertThat(sanitizeShortcode("My Photo (1).PNG")).isEqualTo("My_Photo_1")
        assertThat(sanitizeShortcode("照片")).isEqualTo("sticker")
        assertThat(sanitizeShortcode("a")).isEqualTo("a")
    }
}
```

（`sanitizeShortcode("My Photo (1).PNG")` 语义：去掉扩展名 → 空格替换为 `_` → 过滤为 `[a-zA-Z0-9-_]` → 空则 fallback `sticker`。实现以测试为准。）

- [ ] **Step 2.2: 运行确认编译失败**

Run: `./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.sticker.StickerPackTest"`
Expected: 编译错误（类型未定义）

- [ ] **Step 2.3: 实现模型**

```kotlin
package io.element.android.features.messages.impl.sticker

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.media.ImageInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Immutable
data class StickerImage(
    val shortcode: String,
    val url: String,
    val body: String?,
    val info: ImageInfo?,
)

@Immutable
data class UserStickerPack(
    val displayName: String?,
    val stickers: ImmutableList<StickerImage>,
) {
    fun addSticker(shortcode: String, url: String, body: String?, info: ImageInfo?): UserStickerPack {
        val base = sanitizeShortcode(shortcode)
        var candidate = base
        var suffix = 1
        while (stickers.any { it.shortcode == candidate }) {
            candidate = "$base-${suffix++}"
        }
        return UserStickerPack(
            displayName = displayName,
            stickers = (stickers + StickerImage(shortcode = candidate, url = url, body = body, info = info)).toPersistentList(),
        )
    }
}

fun sanitizeShortcode(raw: String): String {
    val cleaned = raw
        .substringBeforeLast('.')
        .map { c -> if (c.isLetterOrDigit() && c.code < 128) c else if (c.isWhitespace()) '_' else null }
        .filterNotNull()
        .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        .take(100)
        .joinToString("")
    return cleaned.ifEmpty { "sticker" }
}

fun parseUserStickerPack(raw: String?): UserStickerPack {
    val empty = UserStickerPack(displayName = null, stickers = persistentListOf())
    if (raw.isNullOrBlank()) return empty
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return empty
    val displayName = (root["pack"] as? JsonObject)?.get("display_name")?.jsonPrimitive?.content
    val stickers = (root["images"] as? JsonObject)?.entries?.mapNotNull { (shortcode, value) ->
        if (value !is JsonObject) return@mapNotNull null
        val url = value["url"]?.jsonPrimitive?.content ?: return@mapNotNull null
        StickerImage(
            shortcode = shortcode,
            url = url,
            body = value["body"]?.jsonPrimitive?.content,
            info = (value["info"] as? JsonObject)?.toImageInfo(),
        )
    }.orEmpty()
    return UserStickerPack(displayName = displayName, stickers = stickers.toPersistentList())
}

fun serializeUserStickerPack(pack: UserStickerPack): String = buildJsonObject {
    put("pack", buildJsonObject {
        pack.displayName?.let { put("display_name", it) }
        put("usage", listOf("sticker"))
    })
    put("images", buildJsonObject {
        pack.stickers.forEach { sticker ->
            put(sticker.shortcode, buildJsonObject {
                put("url", sticker.url)
                sticker.body?.let { put("body", it) }
                sticker.info?.let { info ->
                    put("info", buildJsonObject {
                        info.width?.let { put("w", it) }
                        info.height?.let { put("h", it) }
                        info.mimetype?.let { put("mimetype", it) }
                        info.size?.let { put("size", it) }
                        info.blurhash?.let { put("blurhash", it) }
                    })
                }
            })
        }
    })
}.toString()

private fun JsonObject.toImageInfo(): ImageInfo = ImageInfo(
    height = getLong("h"),
    width = getLong("w"),
    mimetype = get("mimetype")?.jsonPrimitive?.content,
    size = getLong("size"),
    thumbnailInfo = null,
    thumbnailSource = null,
    blurhash = get("blurhash")?.jsonPrimitive?.content,
)

private fun JsonObject.getLong(key: String): Long? = get(key)?.jsonPrimitive?.content?.toLongOrNull()
```

（import 按编译器提示补齐：`put` 的重载来自 `kotlinx.serialization.json`；`buildJsonObject` 的 `put(String, List<String>)` 重载需 `kotlinx.serialization.json.put`。）

- [ ] **Step 2.4: 运行测试确认通过**

Run: 同 Step 2.2。Expected: PASS（8 个测试）

- [ ] **Step 2.5: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/ features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/sticker/
git commit -m "Add user sticker pack model with MSC2545-compatible JSON

Parses and serializes the im.ponies.user_emotes global account data
format, enforcing the shortcode grammar when adding stickers."
```

---

### Task 3: StickerPicker Presenter（TDD 核心）

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/StickerPickerEvent.kt`
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/StickerPickerState.kt`
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/StickerMediaReader.kt`（接口 + Android 实现）
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/StickerPickerPresenter.kt`
- Test: `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/sticker/StickerPickerPresenterTest.kt`

**DI 决策（已定）**：`StickerPickerPresenter` 用 `@Inject`（无 @Assisted），发送结果通过 `StickerPickerState.sendResult` 暴露（View 观察后关 sheet）；相册 picker 的 `onResult` 回调经 eventSink 转发为 `StickerPicked` 事件。Android 依赖（ContentResolver/BitmapFactory）隔离进 `StickerMediaReader` 接口（`sticker/StickerMediaReader.kt`），生产实现 `@ContributesBinding(AppScope::class)` 用 `@Inject` + `Context`（`@ApplicationContext`？——先 grep 仓库内 context 注入先例，如 `mediaupload` 模块的 `MediaUploadManager`），测试注入 fake。

- [ ] **Step 3.1: Event 与 State**

`StickerPickerEvent.kt`：

```kotlin
package io.element.android.features.messages.impl.sticker

import android.net.Uri

sealed interface StickerPickerEvent {
    data object Dismiss : StickerPickerEvent
    data class SelectSticker(val sticker: StickerImage) : StickerPickerEvent
    data object ImportSticker : StickerPickerEvent
    data class StickerPicked(val uri: Uri, val mimeType: String?, val filename: String?) : StickerPickerEvent
}
```

`StickerPickerState.kt`：

```kotlin
package io.element.android.features.messages.impl.sticker

import androidx.compose.runtime.Immutable
import io.element.android.libraries.architecture.AsyncData
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class StickerPickerState(
    val stickers: AsyncData<ImmutableList<StickerImage>>,
    val isImporting: Boolean,
    val error: StickerPickerError?,
    val sendResult: Boolean?,
    val eventSink: (StickerPickerEvent) -> Unit,
)

@Immutable
enum class StickerPickerError {
    Import,
    Send,
}
```

- [ ] **Step 3.2: `StickerMediaReader` 接口**

```kotlin
package io.element.android.features.messages.impl.sticker

import android.net.Uri
import io.element.android.libraries.matrix.api.media.ImageInfo

interface StickerMediaReader {
    data class StickerMedia(val bytes: ByteArray, val info: ImageInfo?, val filename: String?)

    suspend fun read(uri: Uri): StickerMedia?
}
```

Android 实现（同文件或 `StickerMediaReaderImpl.kt`）：`@Inject` + `@ContributesBinding(AppScope::class)`；构造注入 `@ApplicationContext context: Context`；`read` 在 `CoroutineDispatchers.io` 上：`context.contentResolver.openInputStream(uri)?.use { it.readBytes() }` → `BitmapFactory.Options().apply { inJustDecodeBounds = true }` 解析 w/h/mime（mime 也可从 `contentResolver.getType(uri)`）→ filename 从 `OpenableColumns.DISPLAY_NAME` 查询（cursor 模式，grep `mediaupload` 先例）→ `StickerMedia(bytes, ImageInfo(...), filename)`。**以实际先例为准，此文件是纯 Android 胶水，无单测。**

- [ ] **Step 3.3: 写失败测试**

测试注入边界：`StickerMediaReader` fake（`FakeStickerMediaReader`，`var result: StickerMedia?`）、`FakeMatrixClient`（accountData lambda）、`FakeJoinedRoom`（Task 1 的 sentStickers/givenSendStickerResult）、`FakePickerProvider`（`libraries/mediapickers/test` 已有——**先 grep 其 API**；若其 `registerGalleryPicker` 返回的 launcher 可记录 launch 调用则断言之，否则测试不覆盖 picker 细节）。

```kotlin
package io.element.android.features.messages.impl.sticker

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StickerPickerPresenterTest {
    private val packJson =
        """{"pack":{"display_name":"My stickers","usage":["sticker"]},"images":{"cat":{"url":"mxc://e.org/cat","body":"a cat","info":{"w":512,"h":512,"mimetype":"image/png","size":1}}}}"""

    private fun aMedia(filename: String = "cat.png") = StickerMediaReader.StickerMedia(
        bytes = ByteArray(1),
        info = ImageInfo(width = 512L, height = 512L, mimetype = "image/png", size = 1L, thumbnailInfo = null, thumbnailSource = null, blurhash = null),
        filename = filename,
    )

    @Test
    fun `present - empty account data shows empty stickers`() = runTest {
        createPresenter().test {
            val state = consumeItemsUntilPredicate { it.stickers.isSuccess() }.last()
            assertThat(state.stickers.dataOrNull()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - pack loads from account data`() = runTest {
        createPresenter(accountData = packJson).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            assertThat(state.stickers.dataOrNull()!!.first().shortcode).isEqualTo("cat")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - selecting a sticker sends it and reports success`() = runTest {
        val room = FakeJoinedRoom()
        createPresenter(accountData = packJson, room = room).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            state.eventSink(StickerPickerEvent.SelectSticker(state.stickers.dataOrNull()!!.first()))
            val sent = consumeItemsUntilPredicate { it.sendResult == true }.last()
            assertThat(room.sentStickers).hasSize(1)
            assertThat(room.sentStickers.first().first).isEqualTo("mxc://e.org/cat")
            assertThat(room.sentStickers.first().second).isEqualTo("a cat")
            assertThat(sent.sendResult).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send failure reports failure and keeps stickers`() = runTest {
        val room = FakeJoinedRoom().apply { givenSendStickerResult(Result.failure(IllegalStateException("boom"))) }
        createPresenter(accountData = packJson, room = room).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            state.eventSink(StickerPickerEvent.SelectSticker(state.stickers.dataOrNull()!!.first()))
            val failed = consumeItemsUntilPredicate { it.sendResult == false }.last()
            assertThat(failed.error).isEqualTo(StickerPickerError.Send)
            assertThat(failed.stickers.dataOrNull()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - picking a media uploads it and persists into the pack`() = runTest {
        val client = FakeMatrixClient().apply {
            getAccountDataLambda = { null }
            setAccountDataLambda = { _, content -> savedAccountData = content; Result.success(Unit) }
        }
        createPresenter(matrixClient = client, mediaReader = FakeStickerMediaReader(aMedia())).test {
            val state = consumeItemsUntilPredicate { it.stickers.isSuccess() }.last()
            state.eventSink(
                StickerPickerEvent.StickerPicked(
                    uri = android.net.Uri.parse("content://media/cat.png"),
                    mimeType = "image/png",
                    filename = "cat.png",
                )
            )
            val latest = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 && !it.isImporting }.last()
            val added = latest.stickers.dataOrNull()!!.first()
            assertThat(added.shortcode).isEqualTo("cat")
            assertThat(added.url).isNotEmpty()
            assertThat(client.savedAccountData).contains("\"cat\"")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - upload failure sets import error and does not persist`() = runTest {
        val client = FakeMatrixClient().apply {
            givenUploadMediaResult(Result.failure(IllegalStateException("boom")))
            setAccountDataLambda = { _, content -> savedAccountData = content; Result.success(Unit) }
        }
        createPresenter(matrixClient = client, mediaReader = FakeStickerMediaReader(aMedia())).test {
            val state = consumeItemsUntilPredicate { it.stickers.isSuccess() }.last()
            state.eventSink(
                StickerPickerEvent.StickerPicked(
                    uri = android.net.Uri.parse("content://media/cat.png"),
                    mimeType = "image/png",
                    filename = "cat.png",
                )
            )
            val latest = consumeItemsUntilPredicate { it.error == StickerPickerError.Import }.last()
            assertThat(latest.stickers.dataOrNull()).isEmpty()
            assertThat(client.savedAccountData).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.createPresenter(
        accountData: String? = null,
        room: FakeJoinedRoom = FakeJoinedRoom(),
        matrixClient: FakeMatrixClient = FakeMatrixClient().apply {
            getAccountDataLambda = { accountData }
            setAccountDataLambda = { _, _ -> Result.success(Unit) }
        },
        mediaReader: StickerMediaReader = FakeStickerMediaReader(),
    ) = StickerPickerPresenter(
        room = room,
        matrixClient = matrixClient,
        mediaPickerProvider = FakePickerProvider(),
        stickerMediaReader = mediaReader,
        coroutineDispatchers = testCoroutineDispatchers(),
    )
}

private class FakeStickerMediaReader(var result: StickerMediaReader.StickerMedia? = null) : StickerMediaReader {
    override suspend fun read(uri: android.net.Uri): StickerMediaReader.StickerMedia? = result
}
```

**基建说明**：

- `FakeMatrixClient.savedAccountData`：**先 grep FakeMatrixClient 是否已有记录字段**；若无，本任务在 `FakeMatrixClient.kt` 加 `var savedAccountData: String? = null`（private set，在 `setAccountData` override 内赋值）——文件归入本任务 Files。`AN_AVATAR_URL` 是默认上传返回（mxc），断言 `url` 非空即可。
- `FakePickerProvider`：grep `libraries/mediapickers/test` 的 API；`ImportSticker → picker.launch()` 的断言视 fake 能力取舍（可选）。
- `testCoroutineDispatchers()` 与 `runTest` 配合；若 Presenter 用 `withContext(dispatchers.io)`，`testCoroutineDispatchers` 的 io 已切到 test main。

- [ ] **Step 3.4: 实现 Presenter**

```kotlin
package io.element.android.features.messages.impl.sticker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.mediapickers.api.PickerProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import timber.log.Timber

@Inject
class StickerPickerPresenter(
    private val room: JoinedRoom,
    private val matrixClient: MatrixClient,
    private val mediaPickerProvider: PickerProvider,
    private val stickerMediaReader: StickerMediaReader,
    private val coroutineDispatchers: CoroutineDispatchers,
) : Presenter<StickerPickerState> {
    @Composable
    override fun present(): StickerPickerState {
        val coroutineScope = rememberCoroutineScope()
        var pack by remember { mutableStateOf(UserStickerPack(displayName = null, stickers = persistentListOf())) }
        var isLoading by remember { mutableStateOf(true) }
        var isImporting by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<StickerPickerError?>(null) }
        var sendResult by remember { mutableStateOf<Boolean?>(null) }

        val galleryImagePicker = mediaPickerProvider.registerGalleryPicker { uri, _ ->
            coroutineScope.launch { importSticker(uri) }
        }

        LaunchedEffect(Unit) {
            loadPack()
        }

        fun handleEvent(event: StickerPickerEvent) {
            when (event) {
                StickerPickerEvent.Dismiss -> {
                    sendResult = null
                    error = null
                }
                is StickerPickerEvent.SelectSticker -> coroutineScope.launch {
                    room.sendSticker(
                        url = event.sticker.url,
                        body = event.sticker.body ?: event.sticker.shortcode,
                        info = event.sticker.info,
                    ).onSuccess {
                        sendResult = true
                    }.onFailure {
                        Timber.e(it, "Failed to send sticker in room ${room.roomId.value}")
                        sendResult = false
                        error = StickerPickerError.Send
                    }
                }
                StickerPickerEvent.ImportSticker -> {
                    error = null
                    galleryImagePicker.launch()
                }
                is StickerPickerEvent.StickerPicked -> coroutineScope.launch {
                    importSticker(event.uri)
                }
            }
        }

        return StickerPickerState(
            stickers = if (isLoading) AsyncData.Loading() else AsyncData.Success(pack.stickers),
            isImporting = isImporting,
            error = error,
            sendResult = sendResult,
            eventSink = ::handleEvent,
        )
    }

    private suspend fun loadPack() {
        isLoading = true
        matrixClient.getAccountData(USER_EMOTES_EVENT_TYPE)
            .onSuccess { raw ->
                pack = parseUserStickerPack(raw)
                isLoading = false
            }
            .onFailure {
                Timber.e(it, "Failed to load user sticker pack")
                pack = UserStickerPack(displayName = null, stickers = persistentListOf())
                isLoading = false
            }
    }

    private suspend fun importSticker(uri: android.net.Uri) {
        isImporting = true
        error = null
        val media = stickerMediaReader.read(uri)
        if (media == null) {
            isImporting = false
            error = StickerPickerError.Import
            return
        }
        val mimeType = media.info?.mimetype ?: "image/png"
        matrixClient.uploadMedia(mimeType = mimeType, data = media.bytes)
            .onSuccess { mxcUrl ->
                val updated = pack.addSticker(
                    shortcode = media.filename ?: "sticker",
                    url = mxcUrl,
                    body = media.filename,
                    info = media.info,
                )
                matrixClient.setAccountData(USER_EMOTES_EVENT_TYPE, serializeUserStickerPack(updated))
                    .onSuccess {
                        pack = updated
                        isImporting = false
                    }
                    .onFailure {
                        Timber.e(it, "Failed to persist user sticker pack")
                        isImporting = false
                        error = StickerPickerError.Import
                    }
            }
            .onFailure {
                Timber.e(it, "Failed to upload sticker media")
                isImporting = false
                error = StickerPickerError.Import
            }
    }

    companion object {
        private const val USER_EMOTES_EVENT_TYPE = "im.ponies.user_emotes"
    }
}
```

**注意**：

- `PickerProvider.registerGalleryPicker` 内部用 `rememberLauncherForActivityResult`，**必须在 `present()` 的 Composable 上下文调用**（`MessageComposerPresenter.kt:195` 同款）。
- picker `onResult(uri, mimeType)` 里的 mimeType 弃用（以 `StickerMediaReader` 解析的 info.mimetype 为准）。
- `dismiss` 后 `sendResult` 复位，避免重开 sheet 误关闭。

- [ ] **Step 3.5: 运行测试**

Run: `./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.sticker.*"`
Expected: PASS（含 Task 2 的 StickerPackTest）

- [ ] **Step 3.6: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/ features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/sticker/ libraries/matrix/test/src/main/kotlin/io/element/android/libraries/matrix/test/FakeMatrixClient.kt
git commit -m "Add sticker picker presenter

Loads the user sticker pack from account data, sends m.sticker events
on selection and imports gallery images by uploading them and merging
them into the pack."
```

---

### Task 4: 字符串资源 temporary.xml

**Files:**
- Create: `features/messages/impl/src/main/res/values/temporary.xml`

- [ ] **Step 4.1: 创建文件（若 Task 前序已建则追加条目）**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  ~ Copyright (c) 2026 Element Creations Ltd.
  ~
  ~ SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
  ~ Please see LICENSE files in the repository root for full details.
  -->

<resources>
    <string name="screen_room_attachment_source_sticker">Sticker</string>
    <string name="screen_sticker_picker_title">Stickers</string>
    <string name="screen_sticker_picker_import">Import</string>
    <string name="screen_sticker_picker_empty_title">No stickers yet</string>
    <string name="screen_sticker_picker_empty_subtitle">Import images from your gallery to send them as stickers.</string>
    <string name="a11y_sticker_picker_sticker">Send sticker %1$s</string>
    <string name="a11y_sticker_picker_import">Import a sticker from the gallery</string>
</resources>
```

- [ ] **Step 4.2: 编译确认**

Run: `./gradlew :features:messages:impl:compileDebugKotlin`
Expected: PASS

- [ ] **Step 4.3: Commit**

```
git add features/messages/impl/src/main/res/values/temporary.xml
git commit -m "Add English strings for the sticker picker"
```

---

### Task 5: StickerPickerBottomSheet View + Preview

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/StickerPickerBottomSheet.kt`
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/StickerPickerStatePreviewParam.kt`

- [ ] **Step 5.1: 写 View（结构参照 AttachmentsBottomSheet 的 ModalBottomSheet 用法）**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPickerBottomSheet(
    isVisible: Boolean,
    state: StickerPickerState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isVisible) {
        ModalBottomSheet(
            modifier = modifier,
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            onDismissRequest = onDismiss,
        ) {
            Column(Modifier.navigationBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.screen_sticker_picker_title),
                        style = ElementTheme.typography.fontBodyLgMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.isImporting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(
                            modifier = Modifier.clickable { state.eventSink(StickerPickerEvent.ImportSticker) },
                            imageVector = CompoundIcons.Add(),
                            contentDescription = stringResource(R.string.a11y_sticker_picker_import),
                        )
                    }
                }
                when {
                    state.stickers is AsyncData.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    state.stickers.dataOrNull().isNullOrEmpty() -> IconTitleSubtitleMolecule(
                        icon = BigIcon(Icons.Filled.Outlined.Sticker),
                        title = stringResource(R.string.screen_sticker_picker_empty_title),
                        subtitle = stringResource(R.string.screen_sticker_picker_empty_subtitle),
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(state.stickers.dataOrNull()!!, key = { it.shortcode }) { sticker ->
                            StickerGridItem(sticker = sticker, state = state)
                        }
                    }
                }
                if (state.error != null) {
                    Text(
                        text = stringResource(io.element.android.libraries.ui.strings.CommonStrings.common_error),
                        style = ElementTheme.typography.fontBodySmRegular,
                        color = ElementTheme.colors.textCriticalPrimary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerGridItem(sticker: StickerImage, state: StickerPickerState) {
    val mediaRequest = remember(sticker.url) {
        MediaRequestData(url = sticker.url.let { io.element.android.libraries.matrix.api.media.MediaSource(it).safeUrl })
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .niceClickable { state.eventSink(StickerPickerEvent.SelectSticker(sticker)) },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = mediaRequest,
            contentDescription = stringResource(R.string.a11y_sticker_picker_sticker, sticker.shortcode),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
```

**实现要点**（以实际组件签名为准，先 grep 再写）：

- `AsyncImage` + `MediaRequestData` 加载链路：grep `TimelineItemStickerView.kt` 或 `AttachmentThumbnail` 的用法照抄（`MediaRequestData` 构造可能有 `mimeType` 参数；模块已依赖 `coil.compose`）。
- 发送成功自动关 sheet：`LaunchedEffect(state.sendResult) { if (state.sendResult == true) { state.eventSink(StickerPickerEvent.Dismiss); onDismiss() } }` 放 View 顶层。
- `sendResult == false` 的错误提示：`state.error == Send` 时显示 `common_error` 文案行（或 SnackbarDispatcher——View 无 dispatcher，用文案行最简）。
- `IconTitleSubtitleMolecule`/`BigIcon` 的实际参数签名 grep designsystem（room-message-search 计划 Task 5 已用过）。
- `niceClickable` 的 import 路径 `io.element.android.libraries.designsystem.utils`（grep 确认）。

- [ ] **Step 5.2: PreviewParam + `@PreviewsDayNight`**

```kotlin
internal fun aStickerPickerState(
    stickers: AsyncData<ImmutableList<StickerImage>> = AsyncData.Success(persistentListOf(aStickerImage())),
    isImporting: Boolean = false,
    error: StickerPickerError? = null,
    sendResult: Boolean? = null,
) = StickerPickerState(
    stickers = stickers,
    isImporting = isImporting,
    error = error,
    sendResult = sendResult,
    eventSink = {},
)

internal fun aStickerImage(
    shortcode: String = "cat",
    url: String = "mxc://example.org/cat",
) = StickerImage(shortcode = shortcode, url = url, body = "a cat", info = null)
```

预览覆盖：空态、网格（多贴纸）、导入中、发送失败（error=Send）。包裹 `ElementPreview { }`。

- [ ] **Step 5.3: 编译 + Preview 校验**

Run: `./gradlew :features:messages:impl:compileDebugKotlin`
Expected: PASS（KonsistPreviewTest 若校验 preview 命名规范，跑 `:features:messages:impl:testDebugUnitTest` 确认）

- [ ] **Step 5.4: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/sticker/
git commit -m "Add the sticker picker bottom sheet UI

Renders the user sticker pack in a grid, imports new stickers from
the gallery and reports import/send errors inline."
```

---

### Task 6: Feature flag + Composer 入口 + Messages 接线

**Files:**
- Modify: `libraries/featureflag/api/src/main/kotlin/io/element/android/libraries/featureflag/api/FeatureFlags.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/messagecomposer/MessageComposerEvent.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/messagecomposer/MessageComposerState.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/messagecomposer/MessageComposerPresenter.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/messagecomposer/AttachmentsBottomSheet.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesPresenter.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesState.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesView.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesNode.kt`
- Test: 更新 `MessageComposerPresenterTest` / `MessagesPresenterTest`（既有全字段断言需加默认值）

- [ ] **Step 6.1: `FeatureFlags` 新增 `StickerPicker`**

在 `MessageSearch` 条目后仿写：

```kotlin
StickerPicker(
    key = "feature.sticker_picker",
    title = "Sticker picker",
    description = "Send stickers in rooms using a personal sticker pack.",
    defaultValue = { false },
    isFinished = false,
),
```

- [ ] **Step 6.2: `MessageComposerEvent.PickAttachmentSource.Sticker` + Presenter 处理**

1. `MessageComposerEvent.kt` L30 后加：`data object Sticker : PickAttachmentSource`
2. `MessageComposerPresenter.kt`（L348 Poll 分支旁）：

```kotlin
MessageComposerEvent.PickAttachmentSource.Sticker -> {
    showAttachmentSourcePicker = false
    // Navigation/display of the sticker picker is done at the view layer
}
```

3. `MessageComposerState.kt` 加 `val canSendSticker: Boolean,`（`canShareLocation` L27 旁）；Presenter 返回处（L434 旁）传 `canSendSticker = isStickerPickerEnabled && !isInThread`。
4. Presenter 内 flag 读取（L192 `isSendGalleryMessagesEnabled` 旁）：

```kotlin
val isStickerPickerEnabled by featureFlagService.isFeatureEnabledFlow(FeatureFlags.StickerPicker).collectAsState(initial = false)
```

（线程时间线隐藏：`canSendSticker = isStickerPickerEnabled && !isInThread`——`isInThread` 已有 L158。）

- [ ] **Step 6.3: `AttachmentsBottomSheet` 加 "Sticker" 项**

函数签名加参数 `onSendStickerClick: () -> Unit,`（`onCreatePollClick` L46 旁）；Poll ListItem（L136-143）后加：

```kotlin
if (state.canSendSticker) {
    ListItem(
        modifier = Modifier.clickable {
            state.eventSink(MessageComposerEvent.PickAttachmentSource.Sticker)
            onSendStickerClick()
        },
        leadingContent = ListItemContent.Icon(IconSource.Vector(CompoundIcons.Sticker())),
        content = { Text(stringResource(R.string.screen_room_attachment_source_sticker)) },
    )
}
```

（`AttachmentSourcePickerMenu` 同步加参数；Preview L156-163 传 `onSendStickerClick = {}`。）

- [ ] **Step 6.4: `MessagesPresenter` 聚合 StickerPickerPresenter**

1. 构造注入（L115 customReaction 旁）：`private val stickerPickerPresenter: Presenter<StickerPickerState>,`
2. `present()` 内（L167 旁）：`val stickerPickerState = stickerPickerPresenter.present()`
3. `MessagesState` 加 `val stickerPickerState: StickerPickerState,` 并传入。
4. import `io.element.android.features.messages.impl.sticker.StickerPickerState`。

- [ ] **Step 6.5: `MessagesView` 渲染 sheet + 参数透传**

1. `MessagesView`（L148 旁）与 Composer 渲染处（L305/L508 附近，跟随 `onCreatePollClick` 链路）加 `onSendStickerClick: () -> Unit,`——**实际只需传到 `AttachmentsBottomSheet`**（它渲染于 L525 composer 区域）；`ThreadedMessagesNode`（L321-322 附近）、`MessagesViewWithIdentityChangePreview`（L41-42）等既有调用点补 `onSendStickerClick = {}` 或实际值。
2. Composer 渲染处（L508-526 附近）加 sheet 状态：

```kotlin
var showStickerPicker by rememberSaveable { mutableStateOf(false) }
// onSendStickerClick = { showStickerPicker = true } 传给 AttachmentsBottomSheet
if (showStickerPicker) {
    StickerPickerBottomSheet(
        isVisible = showStickerPicker,
        state = state.stickerPickerState,
        onDismiss = {
            showStickerPicker = false
            state.stickerPickerState.eventSink(StickerPickerEvent.Dismiss)
        },
    )
}
```

（sheet 显示开关持有在 View；`sendResult == true` 时 View 内 LaunchedEffect 自动置 false——见 Task 5 Step 5.1 要点。）

3. `MessagesNode.kt`（L322 旁）：`onCreatePollClick = callback::navigateToCreatePoll,` 之后**无需**新增导航回调（贴纸不走导航，sheet 在 View 内）；`MessagesNode` 调用 `MessagesView` 处的参数链同步。

- [ ] **Step 6.6: 更新既有测试**

- `MessageComposerPresenterTest`：构造处/断言处补 `canSendSticker` 默认值（flag 默认 false → `canSendSticker = false`）；加测试：flag enabled + 非 thread → `canSendSticker == true`；thread 内 → false。
- `MessagesPresenterTest`：`MessagesState` 新字段断言补默认；fake 注入 `Presenter<StickerPickerState>`（lambda presenter 模式，grep 既有测试的 fake presenter 写法）。

- [ ] **Step 6.7: 编译并跑 messages 模块全部单测**

Run: `./gradlew :features:messages:impl:testDebugUnitTest`
Expected: PASS（含既有测试更新）

- [ ] **Step 6.8: Commit**

```
git add libraries/featureflag/api/src/main/kotlin/io/element/android/libraries/featureflag/api/FeatureFlags.kt features/messages/impl/
git commit -m "Wire the sticker picker into the room composer

Adds a Sticker entry to the attachments menu behind the StickerPicker
feature flag, hidden in thread timelines, and renders the sticker
picker bottom sheet from the room screen."
```

---

### Task 7: 验证与收尾

- [ ] **Step 7.1: ktlint**

Run: `./gradlew :features:messages:impl:ktlintFormat :libraries:matrix:impl:ktlintFormat :libraries:matrix:api:ktlintFormat :libraries:matrix:test:ktlintFormat :libraries:featureflag:api:ktlintFormat`
Expected: 自动修复后 `ktlintCheck` PASS

- [ ] **Step 7.2: lint**

Run: `./gradlew :features:messages:impl:lintDebug :libraries:matrix:impl:lintDebug`
Expected: 无新增 error

- [ ] **Step 7.3: 全量单测复核**

Run: `./gradlew :features:messages:impl:testDebugUnitTest :libraries:matrix:test:testDebugUnitTest`
Expected: PASS

- [ ] **Step 7.4: 手动冒烟（有设备时）**

Settings → Labs 开启 `feature.sticker_picker` → 进房间 → 附件菜单 "Sticker" → 空态 → Import 选图 → 网格出现贴纸 → 点击 → 关闭 sheet →（sync 后）时间线出现贴纸；Element Web 同房间可见。线程时间线附件菜单无 "Sticker" 项。flag 关闭时无入口。

- [ ] **Step 7.5: 最终 commit（如有格式修复）**

```
git add -A features/messages/impl libraries/matrix libraries/featureflag
git commit -m "Apply ktlint fixes to the sticker picker"
```

- [ ] **Step 7.6: PR 标签**

- 标题即 changelog（如 "Add a sticker picker to send stickers in rooms"）；一个 `pr-` 标签（建议 `pr-feature`）；因含新 Composable Previews，加 "Record-Screenshots" 标签。

---

## Self-Review 记录

1. **Spec 覆盖**：验收 1（flag/线程隐藏入口）→ Task 6.1/6.2/6.3；验收 2（空态+导入）→ Task 5/3；验收 3（导入持久化）→ Task 2/3（setAccountData 断言）；验收 4（发送 m.sticker）→ Task 1/3/6；验收 5（失败 snackbar/sheet 保持）→ Task 3（error=Send）/ Task 5（错误行）；验收 6（测试+lint）→ Task 3/7。PII → Presenter 仅记 RoomId 与错误类型，符合。
2. **已知实施时需现场确认的点**（已在任务内标注）：`FakeMatrixClient.savedAccountData` 字段需新增（Task 3）；`FakePickerProvider` API（Task 3）；`MediaRequestData`/`AsyncImage` 精确签名（Task 5，grep TimelineItemStickerView）；serialization 传递性（Tech Stack 注记）；`StickerMediaReaderImpl` 的 context 注入先例（Task 3 Step 3.2）；`MessagesView` 参数链的实际插入位置（Task 6.5，跟随 onSendLocationClick 模式）。
3. **类型一致性**：`sendSticker(url, body, info)`（Task 1 定义，Task 3 使用）；`StickerImage/StickerPickerEvent/StickerPickerState` 各任务间一致；`USER_EMOTES_EVENT_TYPE = "im.ponies.user_emotes"`（Task 2 JSON 格式与 Task 3 常量一致）。
4. **PR 规模**：生产代码约 600-700 行（略超 500 上限——Phase 1 含矩阵层 API；若超太多，Task 1 可拆独立 PR）。

