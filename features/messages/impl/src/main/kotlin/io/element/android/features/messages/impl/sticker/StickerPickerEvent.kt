/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import android.net.Uri

sealed interface StickerPickerEvent {
    data object Dismiss : StickerPickerEvent
    data class SelectSticker(val sticker: StickerImage) : StickerPickerEvent
    data object ImportSticker : StickerPickerEvent
    data class StickerPicked(val uri: Uri, val mimeType: String?, val filename: String?) : StickerPickerEvent
}