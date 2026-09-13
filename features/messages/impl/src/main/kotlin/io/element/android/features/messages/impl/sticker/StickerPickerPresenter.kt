/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Inject
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.JoinedRoom
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import timber.log.Timber

@Inject
class StickerPickerPresenter(
    private val room: JoinedRoom,
    private val matrixClient: MatrixClient,
    private val stickerServerClient: StickerServerClient,
) : Presenter<StickerPickerState> {
    @Composable
    override fun present(): StickerPickerState {
        val coroutineScope = rememberCoroutineScope()
        var stickers by remember { mutableStateOf<ImmutableList<StickerImage>>(persistentListOf()) }
        var isLoading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<StickerPickerError?>(null) }
        var sendResult by remember { mutableStateOf<Boolean?>(null) }

        suspend fun loadStickers() {
            isLoading = true
            error = null
            matrixClient.getAccountData(WIDGETS_EVENT_TYPE)
                .onSuccess { raw ->
                    val widgetUrl = extractStickerPickerWidgetUrl(raw)
                    if (widgetUrl == null) {
                        stickers = persistentListOf()
                        isLoading = false
                    } else {
                        stickerServerClient.fetchStickerPacks(widgetUrl)
                            .onSuccess {
                                stickers = it
                                isLoading = false
                            }
                            .onFailure {
                                Timber.e(it, "Failed to load stickers from server")
                                stickers = persistentListOf()
                                error = StickerPickerError.Load
                                isLoading = false
                            }
                    }
                }
                .onFailure {
                    Timber.e(it, "Failed to load sticker picker widget")
                    stickers = persistentListOf()
                    error = StickerPickerError.Load
                    isLoading = false
                }
        }

        LaunchedEffect(Unit) {
            loadStickers()
        }

        fun handleEvent(event: StickerPickerEvent) {
            when (event) {
                StickerPickerEvent.Dismiss -> {
                    sendResult = null
                    error = null
                }
                StickerPickerEvent.Reload -> coroutineScope.launch {
                    loadStickers()
                }
                is StickerPickerEvent.SelectSticker -> coroutineScope.launch {
                    room.sendSticker(
                        url = event.sticker.url,
                        body = event.sticker.body ?: event.sticker.shortcode,
                        info = event.sticker.info,
                    ).onSuccess {
                        sendResult = true
                    }.onFailure {
                        Timber.e(it, "Failed to send sticker in room ${room.roomId.value}")
                        sendResult = false
                        error = StickerPickerError.Send
                    }
                }
            }
        }

        return StickerPickerState(
            stickers = if (isLoading) AsyncData.Loading() else AsyncData.Success(stickers),
            error = error,
            sendResult = sendResult,
            eventSink = ::handleEvent,
        )
    }

    companion object {
        internal const val WIDGETS_EVENT_TYPE = "m.widgets"
    }
}
