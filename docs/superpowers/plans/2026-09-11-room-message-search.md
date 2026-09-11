# 房间内聊天记录搜索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在房间聊天页新增"房间内消息搜索"页：按内容搜索本房间已索引消息，支持按成员（多选）与日期范围筛选结果，点击结果回时间线定位。

**Architecture:** 在 `features/messages/impl` 内新建 `search/` 子包（Appyx Node + Molecule Presenter + 无状态 View），复用 `MessageSearchService.createMessageSearch(scope, roomId)`（该参数此前从未被生产代码使用）；成员/日期为客户端过滤，叠加自动补页策略缓解稀疏结果；导航经 `MessagesFlowNode.NavTarget` 新目标接线，结果点击复用 `viewInTimeline(eventId)`。

**Tech Stack:** Jetpack Compose、Appyx、Molecule、Metro DI、kotlinx.coroutines（Turbine 测试）、Truth 断言。

**设计文档：** `docs/superpowers/specs/2026-09-11-room-message-search-design.md`

---

## 约定（每个任务通用）

- 版权头：复制任意现有文件的 2026 版头（`Copyright (c) 2026 Element Creations Ltd.` + SPDX 两行）。
- 主源集路径前缀：`features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/`
- 测试源集路径前缀：`features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/`
- 包名前缀：`io.element.android.features.messages.impl.search`
- 代码不写注释（仓库规则），KDoc 除外（仅在必要契约处，参考 `MessageSearchService.kt` 风格）。
- 硬换行 160 字符。
- 模块内字符串资源：`features/messages/impl/src/main/res/values/temporary.xml`（**新建**，该模块此前只有 localazy.xml；home 模块的 temporary.xml 是参照）。
- 引用本模块 R 类：`io.element.android.features.messages.impl.R`。
- 通用字符串用 `io.element.android.libraries.ui.strings.CommonStrings`（已含 `common_search`、`action_cancel`、`common_error`、`a11y_clear_search_field` 等，先复用）。

## 可用构件速查（已验证存在）

| 构件 | 位置/签名 |
| --- | --- |
| `MessageSearchService.createMessageSearch(scope, roomId)` | `libraries/matrix/api/.../search/MessageSearchService.kt:35` |
| `MessageSearch` 接口（`results`、`paginationState`、`setQuery`、`paginate`） | `libraries/matrix/api/.../search/MessageSearch.kt` |
| `MessageSearchResult(roomId, eventId, senderId, senderProfile, content, timestamp)` | `libraries/matrix/api/.../search/MessageSearchResult.kt` |
| `MessageSearchPaginationState.Idle(endReached) / Loading` | 同上目录 |
| `FakeMessageSearch` / `FakeMessageSearchService` | `libraries/matrix/test/.../search/` |
| `room.membersStateFlow`（`RoomMembersState`）+ `updateMembers()` | `BaseRoom` / `RoomMembersState.kt`（`activeRoomMembers()` 扩展） |
| `filterMembers(query, coroutineContext)` | `libraries/matrix/api/.../room/FilterRoomMembers.kt:19` |
| `aRoomMember` / `aRoomMemberList` / `FakeJoinedRoom.givenRoomMembersState` | `libraries/matrix/test/.../room/` |
| `aProfileDetailsReady` | `libraries/matrixui/.../reply/InReplyToDetailsPreviewParam.kt:163` |
| `FeatureFlags.MessageSearch` + `FakeFeatureFlagService` | `libraries/featureflag/api` / `test` |
| `CompoundIcons.Search()` / `Close()` / `Filter()` / `User()` / `Calendar()` | `libraries/compound/.../CompoundIcons.kt` |
| `OnVisibleRangeChangeEffect(state) { range -> }` | `libraries/designsystem/.../utils/` |
| `IconTitleSubtitleMolecule` / `BigIcon` | `libraries/designsystem/.../atomic/molecules/` |
| Presenter 测试工具 | `io.element.android.tests.testutils.test { }`（Turbine）、`testCoroutineDispatchers()`、`consumeItemsUntilPredicate` |

**依赖确认**：`features/messages/impl/build.gradle.kts` 已含 `matrix.api`、`matrixui`（经传递）、`dateformatter.api`、`eventformatter.api`、`featureflag.api`、`androidutils`、`designsystem`；test 源集已含 `matrix.test`、`featureflag.test`、`dateformatter.test`、`eventformatter.test`。**无需改 build.gradle.kts。**

**注意**：`senderProfile.getDisambiguatedDisplayName(senderId)` 来自 `io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName`；`isMediaContent()` 来自同包；`toPlainText` 来自 `io.element.android.libraries.matrix.ui.messages`。

---

### Task 1: 筛选模型 `RoomMessageSearchFilter` + 纯过滤逻辑（TDD）

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchFilter.kt`
- Test: `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchFilterTest.kt`

- [ ] **Step 1.1: 写失败测试**

```kotlin
package io.element.android.features.messages.impl.search

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.ProfileDetails
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.ui.messages.reply.aProfileDetailsReady
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class RoomMessageSearchFilterTest {
    private val room = RoomId("!room:server.org")

    private fun aResult(senderId: String = "@alice:server.org", timestamp: Long = 1_000L) = MessageSearchResult(
        roomId = room,
        eventId = io.element.android.libraries.matrix.api.core.EventId("$${senderId.hashCode()}:server.org"),
        senderId = UserId(senderId),
        senderProfile = aProfileDetailsReady(),
        content = MessageContent(body = "body", type = TextMessageType(body = "body")),
        timestamp = timestamp,
    )

    @Test
    fun `empty filter keeps everything`() {
        val results = persistentListOf(aResult(), aResult(senderId = "@bob:server.org"))
        val filtered = results.applyFilter(RoomMessageSearchFilter())
        assertThat(filtered).isEqualTo(results)
    }

    @Test
    fun `sender filter keeps only selected senders`() {
        val results = persistentListOf(aResult(senderId = "@alice:server.org"), aResult(senderId = "@bob:server.org"))
        val filtered = results.applyFilter(
            RoomMessageSearchFilter(senderIds = setOf(UserId("@bob:server.org")))
        )
        assertThat(filtered).hasSize(1)
        assertThat(filtered.first().senderId).isEqualTo(UserId("@bob:server.org"))
    }

    @Test
    fun `date range filter uses inclusive bounds`() {
        val results = persistentListOf(
            aResult(timestamp = 999L),
            aResult(timestamp = 1_000L),
            aResult(timestamp = 2_000L),
            aResult(timestamp = 2_001L),
        )
        val filtered = results.applyFilter(
            RoomMessageSearchFilter(dateRange = 1_000L..2_000L)
        )
        assertThat(filtered.map { it.timestamp }).containsExactly(1_000L, 2_000L).inOrder()
    }

    @Test
    fun `combined filters are ANDed`() {
        val results = persistentListOf(
            aResult(senderId = "@alice:server.org", timestamp = 500L),
            aResult(senderId = "@bob:server.org", timestamp = 500L),
            aResult(senderId = "@bob:server.org", timestamp = 5_000L),
        )
        val filtered = results.applyFilter(
            RoomMessageSearchFilter(senderIds = setOf(UserId("@bob:server.org")), dateRange = 0L..1_000L)
        )
        assertThat(filtered).hasSize(1)
        assertThat(filtered.first().senderId).isEqualTo(UserId("@bob:server.org"))
    }

    @Test
    fun `isFiltering reflects active filters`() {
        assertThat(RoomMessageSearchFilter().isFiltering).isFalse()
        assertThat(RoomMessageSearchFilter(senderIds = setOf(UserId("@a:b.c"))).isFiltering).isTrue()
        assertThat(RoomMessageSearchFilter(dateRange = 0L..1L).isFiltering).isTrue()
    }
}
```

- [ ] **Step 1.2: 运行确认编译失败**

Run: `./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.search.RoomMessageSearchFilterTest"`
Expected: 编译错误（`RoomMessageSearchFilter` 未定义）

- [ ] **Step 1.3: 实现最小代码**

```kotlin
package io.element.android.features.messages.impl.search

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class RoomMessageSearchFilter(
    val senderIds: Set<UserId> = emptySet(),
    val dateRange: LongRange? = null,
) {
    val isFiltering: Boolean = senderIds.isNotEmpty() || dateRange != null
}

fun ImmutableList<MessageSearchResult>.applyFilter(filter: RoomMessageSearchFilter): ImmutableList<MessageSearchResult> {
    if (!filter.isFiltering) return this
    return filterTo ImmutableList(
        predicate = { result ->
            (filter.senderIds.isEmpty() || result.senderId in filter.senderIds) &&
                (filter.dateRange == null || result.timestamp in filter.dateRange)
        },
    )
}
```

（若 `filterTo ImmutableList(predicate=…)` 的具名参数形式不可用，改用 `filter { ... }.toImmutableList()`，import `kotlinx.collections.immutable.toImmutableList`。）

- [ ] **Step 1.4: 运行测试确认通过**

Run: 同 Step 1.2。Expected: PASS（5 个测试）

- [ ] **Step 1.5: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchFilter.kt features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchFilterTest.kt
git commit -m "Add filter model for in-room message search

Introduces RoomMessageSearchFilter with sender and date range
predicates applied client-side to the local search index results."
```

---

### Task 2: Event 与 State 定义

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchEvent.kt`
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchState.kt`

无独立单元测试（sealed 模型由 Presenter 测试覆盖），本任务在 Task 3 的 Presenter 测试中一起验证。

- [ ] **Step 2.1: 创建 Event**

```kotlin
package io.element.android.features.messages.impl.search

import io.element.android.libraries.matrix.api.core.UserId

sealed interface RoomMessageSearchEvent {
    data class UpdateQuery(val query: String) : RoomMessageSearchEvent
    data class ToggleSender(val senderId: UserId) : RoomMessageSearchEvent
    data class SetDateRange(val dateRange: LongRange?) : RoomMessageSearchEvent
    data object LoadMore : RoomMessageSearchEvent
    data class UpdateVisibleRange(val range: IntRange) : RoomMessageSearchEvent
    data class ResultSelected(val eventId: io.element.android.libraries.matrix.api.core.EventId) : RoomMessageSearchEvent
}
```

- [ ] **Step 2.2: 创建 State**

```kotlin
package io.element.android.features.messages.impl.search

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.room.RoomMember
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class RoomMessageSearchState(
    val queryState: TextFieldState,
    val filter: RoomMessageSearchFilter,
    val hasActiveQuery: Boolean,
    val roomMembers: ImmutableList<RoomMember>,
    val results: AsyncData<ImmutableList<RoomMessageSearchResultItem>>,
    val endReached: Boolean,
    val autoPaginationExhausted: Boolean,
    val eventSink: (RoomMessageSearchEvent) -> Unit,
)
```

说明：`hasActiveQuery = queryState.text.isNotEmpty()` 由 Presenter 派生；筛选器仅在有查询时可用。`RoomMessageSearchResultItem` 在 Task 3 定义。

- [ ] **Step 2.3: 编译确认**

Run: `./gradlew :features:messages:impl:compileDebugKotlin`
Expected: FAIL（`RoomMessageSearchResultItem` 尚未定义——预期，在 Task 3 补全；本步骤目的在于确认无其他编译错误）

- [ ] **Step 2.4: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchEvent.kt features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchState.kt
git commit -m "Add state and events for the in-room message search screen"
```

---

### Task 3: Presenter（TDD 核心）

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchResultItem.kt`
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchPresenter.kt`
- Test: `features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchPresenterTest.kt`

- [ ] **Step 3.1: 创建结果项模型（文本/媒体二分，字段比全局搜索精简：无房间上下文）**

```kotlin
package io.element.android.features.messages.impl.search

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import io.element.android.libraries.matrix.api.timeline.item.event.getDisambiguatedDisplayName
import io.element.android.libraries.matrix.ui.components.AttachmentThumbnailType

@Immutable
sealed interface RoomMessageSearchResultItem {
    val eventId: EventId
    val senderId: UserId
    val senderName: String
    val formattedTimestamp: String

    data class Message(
        val messageSearchResult: MessageSearchResult,
        val body: String,
        override val formattedTimestamp: String,
    ) : RoomMessageSearchResultItem {
        override val eventId: EventId = messageSearchResult.eventId
        override val senderId: UserId = messageSearchResult.senderId
        override val senderName: String = messageSearchResult.senderProfile.getDisambiguatedDisplayName(messageSearchResult.senderId)
    }

    data class Media(
        val messageSearchResult: MessageSearchResult,
        val filename: String,
        val caption: String?,
        val formattedSize: String?,
        val thumbnailSource: MediaSource?,
        val thumbnailType: AttachmentThumbnailType,
        override val formattedTimestamp: String,
    ) : RoomMessageSearchResultItem {
        override val eventId: EventId = messageSearchResult.eventId
        override val senderId: UserId = messageSearchResult.senderId
        override val senderName: String = messageSearchResult.senderProfile.getDisambiguatedDisplayName(messageSearchResult.senderId)
    }
}
```

- [ ] **Step 3.2: 写失败测试**

测试辅助构造器（测试文件内 private）：

```kotlin
private fun aResult(
    eventId: String = "$event0:server.org",
    senderId: UserId = UserId("@alice:server.org"),
    timestamp: Long = 0L,
) = MessageSearchResult(
    roomId = ROOM_ID,
    eventId = EventId(eventId),
    senderId = senderId,
    senderProfile = aProfileDetailsReady(),
    content = MessageContent(body = "body", type = TextMessageType(body = "body")),
    timestamp = timestamp,
)
```

（`ROOM_ID = RoomId("!room:server.org")` 为文件级 private val；`EventId("$event0:server.org")` 注意 Kotlin 字符串模板 `$event` 需转义为 `"\$event0:server.org"`。）

测试类骨架（参照 `GlobalSearchPresenterTest.kt` 的 `runTest + presenter.test` 模式）：

```kotlin
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.search

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.androidutils.filesize.FileSizeFormatter
import io.element.android.libraries.androidutils.filesize.FakeFileSizeFormatter
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.search.MessageSearchPaginationState
import io.element.android.libraries.matrix.api.search.MessageSearchService
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.search.FakeMessageSearch
import io.element.android.libraries.matrix.test.search.FakeMessageSearchService
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.ui.messages.reply.aProfileDetailsReady
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomMessageSearchPresenterTest {
    // 见下方各测试
}
```

必须覆盖的测试（完整代码）：

```kotlin
@Test
fun `present - initial state is uninitialized with no filter`() = runTest {
    val presenter = createPresenter()
    presenter.test {
        val state = awaitItem()
        assertThat(state.hasActiveQuery).isFalse()
        assertThat(state.filter.isFiltering).isFalse()
        assertThat(state.results.isUninitialized()).isTrue()
        assertThat(state.endReached).isFalse()
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `present - search service is scoped to the room`() = runTest {
    val service = FakeMessageSearchService()
    createPresenter(messageSearchService = service)
    assertThat(service.lastRoomId).isEqualTo(ROOM_ID)
}

@Test
fun `present - debounced query is forwarded to the search`() = runTest {
    val messageSearch = FakeMessageSearch()
    val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
    presenter.test {
        val state = awaitItem()
        state.queryState.edit { append("tes") }
        advanceTimeBy(100)
        state.queryState.edit { append("test") }
        advanceTimeBy(100)
        assertThat(messageSearch.lastQuery).isNull()
        advanceTimeBy(200)
        assertThat(messageSearch.lastQuery).isEqualTo("test")
        assertThat(messageSearch.setQueryCallCount).isEqualTo(1)
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `present - sender filter keeps only selected senders`() = runTest {
    val messageSearch = FakeMessageSearch()
    val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
    presenter.test {
        val state = awaitItem()
        state.queryState.edit { append("query") }
        advanceUntilIdle()

        messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0"), aResult(eventId = "\$e1", senderId = UserId("@bob:server.org"))))
        val success = consumeItemsUntilPredicate { it.results.dataOrNull()?.size == 2 }.last()

        success.eventSink(RoomMessageSearchEvent.ToggleSender(UserId("@bob:server.org")))
        val filtered = consumeItemsUntilPredicate { state -> (state.results.dataOrNull()?.size ?: 0) == 1 }.last()
        assertThat(filtered.results.dataOrNull()?.first()?.senderId).isEqualTo(UserId("@bob:server.org"))
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `present - date range filter uses inclusive bounds`() = runTest {
    val messageSearch = FakeMessageSearch()
    val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
    presenter.test {
        val state = awaitItem()
        state.queryState.edit { append("query") }
        advanceUntilIdle()

        messageSearch.emitResults(persistentListOf(
            aResult(eventId = "\$e0", timestamp = 999L),
            aResult(eventId = "\$e1", timestamp = 1_000L),
            aResult(eventId = "\$e2", timestamp = 2_000L),
            aResult(eventId = "\$e3", timestamp = 2_001L),
        ))
        consumeItemsUntilPredicate { it.results.dataOrNull()?.size == 4 }

        // Note: dateRange 的语义是"天起始 0 点 ~ 天结束 24 点前"，测试直接以毫秒区间验证
        it.eventSink?.let { sink -> }
        // 用最近状态发事件：
        latestState.eventSink(RoomMessageSearchEvent.SetDateRange(1_000L..2_000L))
        val filtered = consumeItemsUntilPredicate { state -> (state.results.dataOrNull()?.size ?: 0) == 2 }.last()
        assertThat(filtered.results.dataOrNull()!!.map { (it as RoomMessageSearchResultItem.Message).messageSearchResult.timestamp })
            .containsExactly(1_000L, 2_000L).inOrder()
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `present - sparse results after filtering trigger auto pagination up to the limit`() = runTest {
    val messageSearch = FakeMessageSearch()
    val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
    presenter.test {
        val state = awaitItem()
        state.queryState.edit { append("query") }
        advanceUntilIdle()

        state.eventSink(RoomMessageSearchEvent.ToggleSender(UserId("@bob:server.org")))
        // Page with 0 matching sender: must auto-paginate
        messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0", senderId = UserId("@alice:server.org"))))
        advanceUntilIdle()
        // Repeat while paginating succeeds and end is not reached
        repeat(RoomMessageSearchPresenter.MAX_AUTO_PAGINATIONS) {
            messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0", senderId = UserId("@alice:server.org"))))
            advanceUntilIdle()
        }
        assertThat(messageSearch.paginateCallCount).isEqualTo(RoomMessageSearchPresenter.MAX_AUTO_PAGINATIONS)
        val finalState = consumeItemsUntilPredicate { it.autoPaginationExhausted }.last()
        assertThat(finalState.autoPaginationExhausted).isTrue()
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `present - UpdateVisibleRange triggers pagination near the end`() = runTest {
    val messageSearch = FakeMessageSearch()
    val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
    presenter.test {
        val state = awaitItem()
        state.queryState.edit { append("query") }
        advanceUntilIdle()

        messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0")))
        val success = consumeItemsUntilPredicate { it.results.dataOrNull()?.size == 1 }.last()

        success.eventSink(RoomMessageSearchEvent.UpdateVisibleRange(IntRange(0, 0)))
        advanceUntilIdle()
        assertThat(messageSearch.paginateCallCount).isEqualTo(1)
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `present - ResultSelected carries the event id`() = runTest {
    val messageSearch = FakeMessageSearch()
    val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
    presenter.test {
        val state = awaitItem()
        state.queryState.edit { append("query") }
        advanceUntilIdle()

        messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0")))
        val success = consumeItemsUntilPredicate { it.results.dataOrNull()?.size == 1 }.last()

        var received: EventId? = null
        presenter.resultSink = { received = it }   // 若不可注入则改为 navigator fake，见 Step 3.3 说明
        cancelAndIgnoreRemainingEvents()
    }
}
```

**注意**：`latestState` / `presenter.resultSink` 两处伪代码需按 Step 3.3 的最终接口修正——`ResultSelected` 走 Node 回调（navigator 接口方法 `onSearchResultSelected(eventId)`），Presenter 构造参数含 `navigator: RoomMessageSearchNavigator`。测试中用本地 fake：

```kotlin
private class FakeRoomMessageSearchNavigator : RoomMessageSearchNavigator {
    var selectedEventId: EventId? = null
    override fun onSearchResultSelected(eventId: EventId) {
        selectedEventId = eventId
    }
}
```

最后一个测试改为：

```kotlin
@Test
fun `present - ResultSelected carries the event id to the navigator`() = runTest {
    val messageSearch = FakeMessageSearch()
    val navigator = FakeRoomMessageSearchNavigator()
    val presenter = createPresenter(navigator = navigator, messageSearchService = FakeMessageSearchService(messageSearch))
    presenter.test {
        val state = awaitItem()
        state.queryState.edit { append("query") }
        advanceUntilIdle()

        messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0")))
        val success = consumeItemsUntilPredicate { it.results.dataOrNull()?.size == 1 }.last()
        success.eventSink(RoomMessageSearchEvent.ResultSelected(EventId("\$e0")))

        assertThat(navigator.selectedEventId).isEqualTo(EventId("\$e0"))
        cancelAndIgnoreRemainingEvents()
    }
}
```

工厂函数（测试文件底部）：

```kotlin
private fun TestScope.createPresenter(
    navigator: RoomMessageSearchNavigator = FakeRoomMessageSearchNavigator(),
    room: JoinedRoom = FakeJoinedRoom().apply {
        givenRoomMembersState(RoomMembersState.Ready(persistentListOf(aRoomMember(), aRoomMember(userId = UserId("@bob:server.org")))))
    },
    messageSearchService: MessageSearchService = FakeMessageSearchService(),
    dateFormatter: DateFormatter = FakeDateFormatter(),
    fileSizeFormatter: FileSizeFormatter = FakeFileSizeFormatter(),
    latestEventFormatter: RoomLatestEventFormatter = FakeRoomLatestEventFormatter(),
    permalinkParser: PermalinkParser = FakePermalinkParser(),
) = RoomMessageSearchPresenter(
    navigator = navigator,
    room = room,
    messageSearchService = messageSearchService,
    featureFlagService = FakeFeatureFlagService(initialState = mapOf(FeatureFlags.MessageSearch.key to true)),
    dateFormatter = dateFormatter,
    fileSizeFormatter = fileSizeFormatter,
    latestEventFormatter = latestEventFormatter,
    permalinkParser = permalinkParser,
    coroutineDispatchers = testCoroutineDispatchers(),
)
```

（`date range` 测试里删去 `latestState`/`it.eventSink` 两行伪码，直接用最近一次 `consumeItemsUntilPredicate` 返回的 state 发事件。）

- [ ] **Step 3.3: 实现 Presenter**

```kotlin
package io.element.android.features.messages.impl.search

import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.androidutils.filesize.FileSizeFormatter
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.api.DateFormatterMode
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.search.MessageSearch
import io.element.android.libraries.matrix.api.search.MessageSearchPaginationState
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import io.element.android.libraries.matrix.api.search.MessageSearchService
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.isMediaContent
import io.element.android.libraries.matrix.ui.components.AttachmentThumbnailType
import io.element.android.libraries.matrix.ui.messages.toPlainText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

interface RoomMessageSearchNavigator {
    fun onSearchResultSelected(eventId: io.element.android.libraries.matrix.api.core.EventId)
}

@AssistedInject
class RoomMessageSearchPresenter(
    @Assisted private val navigator: RoomMessageSearchNavigator,
    private val room: JoinedRoom,
    private val messageSearchService: MessageSearchService,
    private val featureFlagService: FeatureFlagService,
    private val latestEventFormatter: RoomLatestEventFormatter,
    private val dateFormatter: DateFormatter,
    private val fileSizeFormatter: FileSizeFormatter,
    private val permalinkParser: PermalinkParser,
    private val coroutineDispatchers: CoroutineDispatchers,
) : Presenter<RoomMessageSearchState> {
    @AssistedFactory
    interface Factory {
        fun create(navigator: RoomMessageSearchNavigator): RoomMessageSearchPresenter
    }

    @Composable
    override fun present(): RoomMessageSearchState {
        val coroutineScope = rememberCoroutineScope()
        val queryState = rememberTextFieldState()
        var filter by remember { mutableStateOf(RoomMessageSearchFilter()) }
        val currentMessageSearch: MessageSearch = remember {
            messageSearchService.createMessageSearch(scope = coroutineScope, roomId = room.roomId)
        }
        var autoPaginationCount by remember { mutableIntStateOf(0) }
        var autoPaginationExhausted by remember { mutableStateOf(false) }
        var results: AsyncData<ImmutableList<RoomMessageSearchResultItem>> by remember {
            mutableStateOf(AsyncData.Uninitialized)
        }
        var endReached by remember { mutableStateOf(false) }

        val membersState by room.membersStateFlow.collectAsState()
        LaunchedEffect(Unit) { room.updateMembers() }
        val roomMembers by remember(membersState) {
            derivedStateOf {
                (membersState as? RoomMembersState.Ready)?.roomMembers
                    ?.filter { it.membership.isActive() }
                    .orEmpty()
                    .toImmutableList()
            }
        }

        val isEnabled by produceState(true) {
            featureFlagService.isFeatureEnabledFlow(FeatureFlags.MessageSearch).collectLatest { value = it }
        }

        LaunchedEffect(queryState.text) {
            delay(200.milliseconds)
            if (queryState.text.isNotEmpty()) {
                results = AsyncData.Loading(prevData = results.dataOrNull())
                autoPaginationCount = 0
                autoPaginationExhausted = false
                launch {
                    currentMessageSearch.setQuery(queryState.text.toString())
                        .onFailure { Timber.e(it, "Could not set query for message search") }
                }
            } else {
                results = AsyncData.Uninitialized
            }
        }

        LaunchedEffect(filter) {
            autoPaginationCount = 0
            autoPaginationExhausted = false
        }

        combine(currentMessageSearch.results, currentMessageSearch.paginationState) { r, p -> r to p }
            .collectLatest { (rawResults, paginationState) ->
                endReached = paginationState is MessageSearchPaginationState.Idle && paginationState.endReached
                val filtered = rawResults.applyFilter(filter)
                results = when {
                    queryState.text.isEmpty() -> AsyncData.Uninitialized
                    filtered.isNotEmpty() -> AsyncData.Success(mapResults(filtered))
                    else -> {
                        if (paginationState is MessageSearchPaginationState.Idle && !paginationState.endReached) {
                            if (autoPaginationCount < MAX_AUTO_PAGINATIONS) {
                                autoPaginationCount++
                                launch { currentMessageSearch.paginate() }
                                results = AsyncData.Loading(prevData = results.dataOrNull())
                                return@collectLatest
                            } else {
                                autoPaginationExhausted = true
                            }
                        }
                        if (endReached) {
                            AsyncData.Success(persistentListOf())
                        } else {
                            AsyncData.Loading(prevData = results.dataOrNull())
                        }
                    }
                }
            }

        fun handleEvent(event: RoomMessageSearchEvent) {
            when (event) {
                is RoomMessageSearchEvent.ToggleSender -> {
                    val newSenders = filter.senderIds.toMutableSet()
                    if (event.senderId in newSenders) newSenders.remove(event.senderId) else newSenders.add(event.senderId)
                    filter = filter.copy(senderIds = newSenders)
                }
                is RoomMessageSearchEvent.SetDateRange -> filter = filter.copy(dateRange = event.dateRange)
                is RoomMessageSearchEvent.UpdateVisibleRange -> coroutineScope.launch {
                    val currentCount = results.dataOrNull()?.size ?: 0
                    val canPaginate = currentMessageSearch.paginationState.value.let {
                        it is MessageSearchPaginationState.Idle && !it.endReached
                    }
                    if (event.range.last >= currentCount - 10 && canPaginate) {
                        currentMessageSearch.paginate()
                    }
                }
                RoomMessageSearchEvent.LoadMore -> coroutineScope.launch {
                    autoPaginationCount = 0
                    autoPaginationExhausted = false
                    currentMessageSearch.paginate()
                }
                is RoomMessageSearchEvent.ResultSelected -> navigator.onSearchResultSelected(event.eventId)
                is RoomMessageSearchEvent.UpdateQuery -> queryState.edit { replace(0, length, event.query) }
            }
        }

        return RoomMessageSearchState(
            queryState = queryState,
            filter = filter,
            hasActiveQuery = queryState.text.isNotEmpty() && isEnabled,
            roomMembers = roomMembers,
            results = results,
            endReached = endReached,
            autoPaginationExhausted = autoPaginationExhausted,
            eventSink = ::handleEvent,
        )
    }

    private fun mapResults(results: List<MessageSearchResult>): ImmutableList<RoomMessageSearchResultItem> {
        return results.mapNotNull { result ->
            val formattedTimestamp = dateFormatter.format(
                timestamp = result.timestamp,
                mode = DateFormatterMode.TimeOrDate,
                useRelative = true,
            )
            if (!result.content.isMediaContent()) {
                mapMessageContent(result, formattedTimestamp)
            } else {
                mapMediaContent(result, formattedTimestamp)
            }
        }.toImmutableList()
    }

    private fun mapMessageContent(result: MessageSearchResult, formattedTimestamp: String): RoomMessageSearchResultItem.Message? {
        val body = latestEventFormatter.format(
            latestEvent = io.element.android.libraries.matrix.api.roomlist.LatestEventValue.Remote(
                timestamp = result.timestamp,
                content = result.content,
                senderId = result.senderId,
                senderProfile = result.senderProfile,
                isOwn = false,
            ),
            isDmRoom = false,
        )
        return RoomMessageSearchResultItem.Message(
            messageSearchResult = result,
            body = body?.toString() ?: "",
            formattedTimestamp = formattedTimestamp,
        )
    }

    private fun mapMediaContent(result: MessageSearchResult, formattedTimestamp: String): RoomMessageSearchResultItem.Media? {
        val content = result.content
        if (content !is MessageContent || content.type !is MessageTypeWithAttachment) return null
        val messageType = content.type as MessageTypeWithAttachment
        val caption = messageType.toPlainText(permalinkParser, default = messageType.caption ?: messageType.filename)
        val thumbnailType = when (messageType) {
            is ImageMessageType -> AttachmentThumbnailType.Image
            is VideoMessageType -> AttachmentThumbnailType.Video
            is AudioMessageType -> AttachmentThumbnailType.Audio
            is VoiceMessageType -> AttachmentThumbnailType.Voice
            is FileMessageType -> AttachmentThumbnailType.File
            is StickerMessageType -> AttachmentThumbnailType.Image
        }
        val thumbnailSource = when (messageType) {
            is ImageMessageType -> messageType.info?.thumbnailSource ?: messageType.source
            is VideoMessageType -> messageType.info?.thumbnailSource
            else -> null
        }
        val formattedSize = when (messageType) {
            is ImageMessageType -> messageType.info?.size
            is VideoMessageType -> messageType.info?.size
            is AudioMessageType -> messageType.info?.size
            is VoiceMessageType -> messageType.info?.size
            is FileMessageType -> messageType.info?.size
            is StickerMessageType -> messageType.info?.size
        }?.let(fileSizeFormatter::format)
        return RoomMessageSearchResultItem.Media(
            messageSearchResult = result,
            filename = messageType.filename,
            caption = caption,
            formattedSize = formattedSize,
            thumbnailSource = thumbnailSource,
            thumbnailType = thumbnailType,
            formattedTimestamp = formattedTimestamp,
        )
    }

    companion object {
        const val MAX_AUTO_PAGINATIONS = 10
    }
}
```

实现要点（与伪码测试对齐）：
- `collectLatest` 的直接调用需包在 `LaunchedEffect(Unit)` 内（Molecule 风格），且其 receiver scope 是 `CoroutineScope`，`launch` 可用。
- 若 `LatestEventValue.Remote` 的 `isOwn` 参数在全局搜索里由 `matrixClient.isMe(...)` 提供，此处用 `false`（房间内搜索页无 MatrixClient 依赖，发件人高亮不必要）。

- [ ] **Step 3.4: 运行测试**

Run: `./gradlew :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.search.*"`
Expected: 全部 PASS；若自动补页测试不稳定（collectLatest 重入导致重复计数），把补页判断从 `collectLatest` 移到对 `results`+`paginationState` 的 `LaunchedEffect` 观察中，保持上限断言不变。

- [ ] **Step 3.5: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/ features/messages/impl/src/test/kotlin/io/element/android/features/messages/impl/search/
git commit -m "Add presenter for in-room message search

Debounces the query into the scoped MessageSearch cursor, applies
sender and date range filters client-side and auto-paginates when
filters thin the results below one page, up to a fixed limit."
```

---

### Task 4: 字符串资源 temporary.xml

**Files:**
- Create: `features/messages/impl/src/main/res/values/temporary.xml`

- [ ] **Step 4.1: 创建文件**

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  ~ Copyright (c) 2026 Element Creations Ltd.
  ~
  ~ SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
  ~ Please see LICENSE files in the repository root for full details.
  -->

<resources>
    <string name="screen_room_message_search_title">Search in room</string>
    <string name="screen_room_message_search_search_hint">Search messages</string>
    <string name="screen_room_message_search_start_title">Search this room\'s messages</string>
    <string name="screen_room_message_search_start_subtitle">Only messages received while message search was enabled are indexed.</string>
    <string name="screen_room_message_search_no_results_title">No results</string>
    <string name="screen_room_message_search_no_results_subtitle">No indexed messages match this search. Try different keywords or remove some filters.</string>
    <string name="screen_room_message_search_filter_members">Filter by member</string>
    <string name="screen_room_message_search_filter_from">From</string>
    <string name="screen_room_message_search_filter_date">Date</string>
    <string name="screen_room_message_search_date_any">Any date</string>
    <string name="screen_room_message_search_date_today">Today</string>
    <string name="screen_room_message_search_date_last_7_days">Last 7 days</string>
    <string name="screen_room_message_search_date_last_30_days">Last 30 days</string>
    <string name="screen_room_message_search_date_custom">Custom range</string>
    <string name="screen_room_message_search_load_more">Load more</string>
    <string name="a11y_room_message_search_select_members">Filter results by member</string>
</resources>
```

- [ ] **Step 4.2: 编译确认资源生成**

Run: `./gradlew :features:messages:impl:compileDebugKotlin`
Expected: PASS

- [ ] **Step 4.3: Commit**

```
git add features/messages/impl/src/main/res/values/temporary.xml
git commit -m "Add English strings for the in-room message search screen"
```

---

### Task 5: View + 成员选择器 + 日期选择器

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchView.kt`
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchStatePreviewParam.kt`

UI 组成（自上而下）：
1. `Scaffold` + `TopAppBar`：`BackButton` + `FilledTextField`（自动聚焦，参照 GlobalSearchView 的 FocusRequester 模式）+ 清除按钮（`CompoundIcons.Close()`）
2. 筛选行（`hasActiveQuery` 时显示，否则隐藏）：横向滚动 `Row` —— 成员 chips（选中成员，`CompoundIcons.Close()` 移除）+ "成员" chip（`CompoundIcons.User()`，点击开 ModalBottomSheet）+ 日期 chip（`CompoundIcons.Calendar()`，显示当前区间或 "Any date"，点击开选择 dialog）
3. 结果 `LazyColumn` + `OnVisibleRangeChangeEffect`：
   - `Uninitialized` → `IconTitleSubtitleMolecule`（start 提示，含索引范围副标题）
   - `Loading` → `LinearProgressIndicator`
   - 空 Success → `IconTitleSubtitleMolecule`（no results）
   - Success → 结果项（`Message`：sender 头像 + senderName + formattedTimestamp + body；`Media`：缩略图 + filename/caption）
   - `autoPaginationExhausted` → 底部 "Load more" 按钮（`eventSink(LoadMore)`）
4. 成员选择器：`ModalBottomSheet`（`io.element.android.libraries.designsystem.theme.components.ModalBottomSheet`，先 grep 确认该组件存在与签名；若名称不同用仓库实际组件）内含 `FilledTextField`（本地过滤）+ 成员列表（复选态：已选成员头像带 `CompoundIcons.Check()` 覆盖或背景高亮，点击 `ToggleSender`）
5. 日期选择：`AlertDialog` 风格 dialog，内含预设 chips（Any/Today/7d/30d）+ "Custom range" 打开 Material3 `DateRangePicker`（`androidx.compose.material3.DateRangePicker` + `rememberDateRangePickerState`；起止换算：起点=选中起始日 00:00 本地时区，终点=选中结束日次日 00:00 - 1，即 `LocalDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()`）

- [ ] **Step 5.1: 先 grep 确认 ModalBottomSheet 组件签名**

Run: grep "fun ModalBottomSheet" `libraries/designsystem/src/main/kotlin` 目录；按实际签名写成员选择器。（PinnedMessagesListView / RoomMemberListView 等已有先例可参照。）

- [ ] **Step 5.2: 写 View**

结果项渲染（直接给出关键代码）：

```kotlin
@Composable
private fun LazyListScope.resultItems(
    results: ImmutableList<RoomMessageSearchResultItem>,
    onResultClick: (EventId) -> Unit,
) {
    itemsIndexed(
        items = results,
        contentType = { _, item -> item::class.java },
        key = { _, item -> item.eventId },
    ) { index, result ->
        val modifier = Modifier
            .fillMaxWidth()
            .niceClickable { onResultClick(result.eventId) }
        when (result) {
            is RoomMessageSearchResultItem.Message -> ResultMessageItem(result, modifier)
            is RoomMessageSearchResultItem.Media -> ResultMediaItem(result, modifier)
        }
    }
}

@Composable
private fun ResultMessageItem(result: RoomMessageSearchResultItem.Message, modifier: Modifier) {
    Row(modifier = modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(
            avatarData = AvatarData(id = result.senderId.value, name = result.senderName, size = AvatarSize.RoomListItem),
            avatarType = AvatarType.Room(heroes = persistentListOf(), isTombstoned = false),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(result.senderName, maxLines = 1, style = ElementTheme.typography.fontBodyLgMedium, modifier = Modifier.weight(1f))
                Text(result.formattedTimestamp, maxLines = 1, style = ElementTheme.typography.fontBodySmRegular, color = ElementTheme.colors.textSecondary)
            }
            Text(result.body, maxLines = 2, overflow = TextOverflow.Ellipsis, style = ElementTheme.typography.fontBodyMdRegular, color = ElementTheme.colors.textSecondary)
        }
    }
}
```

（`ResultMediaItem` 同构：`AttachmentThumbnail` + filename/caption/size，参照 GlobalSearchView 的 `MediaMessageSearchResultItemView`。）

日期预设逻辑（Presenter 外、View 内纯函数即可）：

```kotlin
private fun presetDateRange(preset: DatePreset, today: LocalDate = LocalDate.now()): LongRange? = when (preset) {
    DatePreset.ANY -> null
    DatePreset.TODAY -> today.atStartOfDay()
    DatePreset.LAST_7_DAYS -> today.minusDays(6).atStartOfDay()
    DatePreset.LAST_30_DAYS -> today.minusDays(29).atStartOfDay()
    DatePreset.CUSTOM -> null
}

private fun LocalDate.atStartOfDay(): LongRange {
    val start = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endExclusive = plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return start until endExclusive
}
```

（`TODAY`/`LAST_*` 的区间起点即 preset 起始日 `atStartOfDay()`，终点统一为"今天结束"。）

- [ ] **Step 5.3: 写 PreviewParam + `@PreviewsDayNight` 预览**

覆盖状态：默认（无查询）、查询+Loading、文本结果、媒体结果、空结果、带成员筛选 chips、自动补页耗尽（Load more 可见）。Preview 布局参照 `GlobalSearchStatePreviewParam` + `ElementPreview { }` 包裹。fixture 函数 `aRoomMessageSearchState(...)`、`aRoomMessageSearchResult(...)` 放 PreviewParam 文件（`internal fun`，参照 home 模块的 `aMessageSearchResult` 做法）。

- [ ] **Step 5.4: 编译 + 运行全部 search 测试**

Run: `./gradlew :features:messages:impl:compileDebugKotlin :features:messages:impl:testDebugUnitTest --tests "io.element.android.features.messages.impl.search.*"`
Expected: PASS

- [ ] **Step 5.5: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/
git commit -m "Add the in-room message search screen UI

Renders filtered text and media results with member and date range
filter chips, a member picker bottom sheet and a date range dialog."
```

---

### Task 6: 导航接线（Node + NavTarget + TopBar 入口）

**Files:**
- Create: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/search/RoomMessageSearchNode.kt`
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesFlowNode.kt`（NavTarget ~L199、resolve()、MessagesNode.Callback 实现处）
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesNode.kt`（Callback 接口 ~L126）
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesView.kt`（MessagesMenuActions ~L461 + 调用处 ~L256）
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesState.kt`（新增 `isRoomMessageSearchEnabled: Boolean`）
- Modify: `features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/MessagesPresenter.kt`（produceState 读取 flag → state）

- [ ] **Step 6.1: 创建 RoomMessageSearchNode**

```kotlin
package io.element.android.features.messages.impl.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.libraries.di.RoomScope

@ContributesNode(RoomScope::class)
@AssistedInject
class RoomMessageSearchNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: RoomMessageSearchPresenter.Factory,
) : Node(buildContext, plugins = plugins) {
    private val presenter = presenterFactory.create(
        navigator = object : RoomMessageSearchNavigator {
            override fun onSearchResultSelected(eventId: io.element.android.libraries.matrix.api.core.EventId) {
                // wired in Task 7 via callback plugin
            }
        }
    )

    @Composable
    override fun View(modifier: Modifier) {
        val state = presenter.present()
        RoomMessageSearchView(
            state = state,
            onBackClick = ::navigateUp,
            modifier = modifier,
        )
    }
}
```

**注意**：`onSearchResultSelected` 必须转发到 FlowNode 的 `viewInTimeline(eventId)`（先 pop 搜索页，再定位）。做法与 `PinnedMessagesListNode.Callback` 相同——在 Node 上定义 `interface Callback : Plugin { fun viewInTimeline(eventId: EventId) }`，`callback()` 获取，`MessagesFlowNode.resolve()` 里实现；`viewInTimeline` 走 `handlePermalinkClick(permalinkData, pushToBackstack = false)`，该回调由外层处理时会先把 FlowNode 的 backstack pop 回 Messages 目标（与 PinnedMessagesList 相同路径——它同样从子页返回主时间线）。

- [ ] **Step 6.2: MessagesFlowNode 接线**

1. `NavTarget` 增加（`ThreadsList` 之后）：

```kotlin
@Parcelize
data object RoomMessageSearch : NavTarget
```

2. `import ...features.messages.impl.search.RoomMessageSearchNode`，`resolve()` 增加分支（参照 `PinnedMessagesList` 分支写法）：

```kotlin
NavTarget.RoomMessageSearch -> {
    val callback = object : RoomMessageSearchNode.Callback {
        override fun viewInTimeline(eventId: EventId) {
            this@MessagesFlowNode.viewInTimeline(eventId)
        }
    }
    createNode<RoomMessageSearchNode>(buildContext, plugins = listOf(callback))
}
```

3. `MessagesNode.Callback` 接口增加 `fun navigateToRoomMessageSearch()`；`MessagesFlowNode` 内 `MessagesNode` 的 callback object（resolve 的 Messages 分支 ~L253-371）增加实现：

```kotlin
override fun navigateToRoomMessageSearch() {
    backstack.push(NavTarget.RoomMessageSearch)
}
```

- [ ] **Step 6.3: flag 下传链路**

`MessagesPresenter.present()` 中（`canOpenThreadList` 旁，~L179）：

```kotlin
val isRoomMessageSearchEnabled by featureFlagService.isFeatureEnabledFlow(FeatureFlags.MessageSearch).collectAsState(initial = false)
```

`MessagesState` 增加字段 `val isRoomMessageSearchEnabled: Boolean,`（放在 `threads` 附近）；Presenter 构造处传入。MessagesPresenterTest 的既有 state 断言与 fixture（`aMessagesState` 若存在）需同步加默认参数 `isRoomMessageSearchEnabled = false`。

- [ ] **Step 6.4: TopBar 图标**

`MessagesView.kt`：

1. `MessagesMenuActions`（L461）增加参数 `displaySearch: Boolean, onSearchClick: () -> Unit,`，函数体开头：

```kotlin
if (displaySearch) {
    Icon(
        modifier = Modifier.clickable(enabled = true, onClick = onSearchClick),
        imageVector = CompoundIcons.Search(),
        contentDescription = stringResource(R.string.screen_room_message_search_title),
    )
    Spacer(Modifier.width(8.dp))
}
```

2. 调用处（L256）传 `displaySearch = state.isRoomMessageSearchEnabled && state.timelineState.timelineMode !is Timeline.Mode.Thread`、`onSearchClick = onRoomMessageSearchClick`。

3. `MessagesView` 增加参数 `onRoomMessageSearchClick: () -> Unit,`；`MessagesNode.View` 调用处传 `onRoomMessageSearchClick = callback::navigateToRoomMessageSearch`。

4. TopBar 的 a11y contentDescription 用 Task 4 的 `screen_room_message_search_title`。

- [ ] **Step 6.5: 编译并跑 messages 模块全部单测**

Run: `./gradlew :features:messages:impl:testDebugUnitTest`
Expected: PASS（含既有测试；`MessagesPresenterTest` 若有全字段断言需按新默认参数更新）

- [ ] **Step 6.6: Commit**

```
git add features/messages/impl/src/main/kotlin/io/element/android/features/messages/impl/
git commit -m "Wire in-room message search into the room screen

Adds a search icon to the room top bar behind the MessageSearch
feature flag, a RoomMessageSearch nav target and result tap through
to focus the event in the timeline."
```

---

### Task 7: 验证与收尾

- [ ] **Step 7.1: ktlint**

Run: `./gradlew :features:messages:impl:ktlintFormat`
Expected: 无输出/自动修复后再 `ktlintCheck` PASS

- [ ] **Step 7.2: lint**

Run: `./gradlew :features:messages:impl:lintDebug`
Expected: 无新增 error

- [ ] **Step 7.3: 全量单测复核**

Run: `./gradlew :features:messages:impl:testDebugUnitTest`
Expected: PASS

- [ ] **Step 7.4: 手动冒烟（有设备时）**

开启 `feature_message_search` flag → 进房间 → TopBar 搜索图标 → 输入关键词 → 成员/日期筛选 → 点击结果回时间线定位。

- [ ] **Step 7.5: 最终 commit（如有格式修复）**

```
git add -A features/messages/impl
git commit -m "Apply ktlint fixes to the in-room message search"
```

---

## Self-Review 记录

1. **Spec 覆盖**：验收 1（flag→图标）→ Task 6.3/6.4；验收 2（查询+分页）→ Task 3/5；验收 3（成员多选过滤）→ Task 1/3/5；验收 4（日期边界）→ Task 1/3/5；验收 5（点击定位）→ Task 3/6；验收 6（空态+索引提示）→ Task 4/5；验收 7（测试+lint）→ Task 3/7。PII（不日志查询与消息体）→ Presenter 仅 `Timber.e(it, "Could not set query...")` 不含文本，符合。
2. **占位符**：Task 5 的 ModalBottomSheet 签名需 grep 确认（Step 5.1），其余代码完整。
3. **类型一致性**：`RoomMessageSearchFilter(senderIds, dateRange)`、`applyFilter`、`MAX_AUTO_PAGINATIONS = 10`、`RoomMessageSearchNavigator.onSearchResultSelected(EventId)` 在各任务间一致；测试伪码的修正说明已并入 Task 3。
