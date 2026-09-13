/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.libraries.architecture.AsyncData
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

internal fun aStickerPickerState(
    stickers: AsyncData<ImmutableList<StickerImage>> = AsyncData.Success(persistentListOf(aStickerImage())),
    error: StickerPickerError? = null,
    sendResult: Boolean? = null,
) = StickerPickerState(
    stickers = stickers,
    error = error,
    sendResult = sendResult,
    eventSink = {},
)

internal fun aStickerImage(
    shortcode: String = "cat",
    url: String = "mxc://example.org/cat",
) = StickerImage(shortcode = shortcode, url = url, body = "a cat", info = null)

class StickerPickerStatePreviewParam : PreviewParameterProvider<StickerPickerState> {
    override val values: Sequence<StickerPickerState> = sequenceOf(
        aStickerPickerState(
            stickers = AsyncData.Success(persistentListOf()),
        ),
        aStickerPickerState(
            stickers = AsyncData.Success(
                persistentListOf(
                    aStickerImage(shortcode = "cat", url = "mxc://example.org/cat"),
                    aStickerImage(shortcode = "dog", url = "mxc://example.org/dog"),
                    aStickerImage(shortcode = "moon", url = "mxc://example.org/moon"),
                )
            ),
        ),
        aStickerPickerState(error = StickerPickerError.Load, stickers = AsyncData.Success(persistentListOf())),
        aStickerPickerState(error = StickerPickerError.Send),
        aStickerPickerState(
            stickers = AsyncData.Loading(),
        ),
    )
}
