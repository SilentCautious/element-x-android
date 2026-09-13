/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.search

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UserId

sealed interface RoomMessageSearchEvent {
    data class UpdateQuery(val query: String) : RoomMessageSearchEvent
    data class ToggleSender(val senderId: UserId) : RoomMessageSearchEvent
    data class SetDateRange(val dateRange: LongRange?) : RoomMessageSearchEvent
    data object LoadMore : RoomMessageSearchEvent
    data class UpdateVisibleRange(val range: IntRange) : RoomMessageSearchEvent
    data class ResultSelected(val eventId: EventId) : RoomMessageSearchEvent
}
