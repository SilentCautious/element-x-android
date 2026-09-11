/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.search

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.TextMessageType
import io.element.android.libraries.matrix.ui.messages.reply.aProfileDetailsReady
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class RoomMessageSearchFilterTest {
    private val room = RoomId("!room:server.org")

    private fun aResult(senderId: String = "@alice:server.org", timestamp: Long = 1_000L) = MessageSearchResult(
        roomId = room,
        eventId = EventId("\$${senderId.hashCode()}:server.org"),
        senderId = UserId(senderId),
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
