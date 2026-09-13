/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import androidx.compose.runtime.Immutable
import io.element.android.libraries.architecture.AsyncData
import kotlinx.collections.immutable.ImmutableList

@Immutable
data class StickerPickerState(
    val stickers: AsyncData<ImmutableList<StickerImage>>,
    val error: StickerPickerError?,
    val sendResult: Boolean?,
    val eventSink: (StickerPickerEvent) -> Unit,
)

@Immutable
enum class StickerPickerError {
    Load,
    Send,
}
