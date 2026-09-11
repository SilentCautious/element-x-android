/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.search

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class RoomMessageSearchFilter(
    val senderIds: Set<UserId> = emptySet(),
    val dateRange: LongRange? = null,
) {
    val isFiltering: Boolean = senderIds.isNotEmpty() || dateRange != null
}

fun ImmutableList<MessageSearchResult>.applyFilter(filter: RoomMessageSearchFilter): ImmutableList<MessageSearchResult> {
    if (!filter.isFiltering) return this
    return filter { result ->
        (filter.senderIds.isEmpty() || result.senderId in filter.senderIds) &&
            (filter.dateRange == null || result.timestamp in filter.dateRange)
    }.toImmutableList()
}
