/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

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
