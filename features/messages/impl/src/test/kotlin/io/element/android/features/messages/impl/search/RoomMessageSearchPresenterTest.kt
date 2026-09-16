/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.search

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.filesize.FakeFileSizeFormatter
import io.element.android.libraries.androidutils.filesize.FileSizeFormatter
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.test.FakeDateFormatter
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.eventformatter.test.FakeRoomLatestEventFormatter
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import io.element.android.libraries.matrix.api.search.MessageSearchService
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.room.aRoomMember
import io.element.android.libraries.matrix.test.search.FakeMessageSearch
import io.element.android.libraries.matrix.test.search.FakeMessageSearchService
import io.element.android.libraries.matrix.ui.messages.reply.aProfileDetailsReady
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomMessageSearchPresenterTest {
    @Test
    fun `present - search service is scoped to the room`() = runTest {
        val service = FakeMessageSearchService()
        val presenter = createPresenter(messageSearchService = service)
        presenter.test {
            awaitItem()
            assertThat(service.lastRoomId).isEqualTo(A_ROOM_ID)
        }
    }

    @Test
    fun `present - debounced fuzzy query is forwarded to the search`() = runTest {
        val messageSearch = FakeMessageSearch()
        val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
        presenter.test {
            val state = awaitItem()
            state.queryState.edit { replace(0, length, "tes") }
            advanceTimeBy(100)
            state.queryState.edit { replace(0, length, "test") }
            advanceTimeBy(100)
            assertThat(messageSearch.lastFuzzyQuery).isNull()
            advanceTimeBy(200)
            assertThat(messageSearch.lastFuzzyQuery).isEqualTo("test")
            assertThat(messageSearch.setFuzzyQueryCallCount).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - sender filter keeps only selected senders`() = runTest {
        val messageSearch = FakeMessageSearch()
        val presenter = createPresenter(messageSearchService = FakeMessageSearchService(messageSearch))
        presenter.test {
            val state = awaitItem()
            state.queryState.edit { replace(0, length, "query") }
            advanceUntilIdle()

            messageSearch.emitResults(
                persistentListOf(
                    aResult(eventId = "\$e0"),
                    aResult(eventId = "\$e1", senderId = UserId("@bob:server.org")),
                )
            )
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
            state.queryState.edit { replace(0, length, "query") }
            advanceUntilIdle()

            messageSearch.emitResults(
                persistentListOf(
                    aResult(eventId = "\$e0", timestamp = 999L),
                    aResult(eventId = "\$e1", timestamp = 1_000L),
                    aResult(eventId = "\$e2", timestamp = 2_000L),
                    aResult(eventId = "\$e3", timestamp = 2_001L),
                )
            )
            val success = consumeItemsUntilPredicate { it.results.dataOrNull()?.size == 4 }.last()
            success.eventSink(RoomMessageSearchEvent.SetDateRange(1_000L..2_000L))
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
            state.queryState.edit { replace(0, length, "query") }
            advanceUntilIdle()

            state.eventSink(RoomMessageSearchEvent.ToggleSender(UserId("@bob:server.org")))
            messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0", senderId = UserId("@alice:server.org"))))
            advanceUntilIdle()
            repeat(RoomMessageSearchPresenter.MAX_AUTO_PAGINATIONS + 1) { index ->
                messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e$index", senderId = UserId("@alice:server.org"))))
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
            state.queryState.edit { replace(0, length, "query") }
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
    fun `present - ResultSelected carries the event id to the navigator`() = runTest {
        val messageSearch = FakeMessageSearch()
        val navigator = FakeRoomMessageSearchNavigator()
        val presenter = createPresenter(navigator = navigator, messageSearchService = FakeMessageSearchService(messageSearch))
        presenter.test {
            val state = awaitItem()
            state.queryState.edit { replace(0, length, "query") }
            advanceUntilIdle()

            messageSearch.emitResults(persistentListOf(aResult(eventId = "\$e0")))
            val success = consumeItemsUntilPredicate { it.results.dataOrNull()?.size == 1 }.last()
            success.eventSink(RoomMessageSearchEvent.ResultSelected(EventId("\$e0")))

            assertThat(navigator.selectedEventId).isEqualTo(EventId("\$e0"))
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private class FakeRoomMessageSearchNavigator : RoomMessageSearchNavigator {
    var selectedEventId: EventId? = null
        private set

    override fun onSearchResultSelected(eventId: EventId) {
        selectedEventId = eventId
    }
}

private fun aResult(
    eventId: String = "\$event0:server.org",
    senderId: UserId = UserId("@alice:server.org"),
    timestamp: Long = 0L,
) = MessageSearchResult(
    roomId = A_ROOM_ID,
    eventId = EventId(eventId),
    senderId = senderId,
    senderProfile = aProfileDetailsReady(),
    content = MessageContent(
        body = "body",
        inReplyTo = null,
        isEdited = false,
        threadInfo = null,
        type = TextMessageType(body = "body", formatted = null),
    ),
    timestamp = timestamp,
)

private fun TestScope.createPresenter(
    navigator: RoomMessageSearchNavigator = FakeRoomMessageSearchNavigator(),
    room: JoinedRoom = FakeJoinedRoom().apply {
        baseRoom.givenUpdateMembersResult {}
        givenRoomMembersState(
            RoomMembersState.Ready(
                persistentListOf(
                    aRoomMember(),
                    aRoomMember(userId = UserId("@bob:server.org")),
                )
            )
        )
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
