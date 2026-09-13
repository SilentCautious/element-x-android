/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

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
