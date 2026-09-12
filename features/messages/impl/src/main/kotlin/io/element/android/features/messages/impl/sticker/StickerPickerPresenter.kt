/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import android.net.Uri
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
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.mediapickers.api.PickerProvider
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import timber.log.Timber

@Inject
class StickerPickerPresenter(
    private val room: JoinedRoom,
    private val matrixClient: MatrixClient,
    private val mediaPickerProvider: PickerProvider,
    private val stickerMediaReader: StickerMediaReader,
    private val coroutineDispatchers: CoroutineDispatchers,
) : Presenter<StickerPickerState> {
    @Composable
    override fun present(): StickerPickerState {
        val coroutineScope = rememberCoroutineScope()
        var pack by remember { mutableStateOf(UserStickerPack(displayName = null, stickers = persistentListOf())) }
        var isLoading by remember { mutableStateOf(true) }
        var isImporting by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<StickerPickerError?>(null) }
        var sendResult by remember { mutableStateOf<Boolean?>(null) }

        suspend fun loadPack() {
            isLoading = true
            matrixClient.getAccountData(USER_EMOTES_EVENT_TYPE)
                .onSuccess { raw ->
                    pack = parseUserStickerPack(raw)
                    isLoading = false
                }
                .onFailure {
                    Timber.e(it, "Failed to load user sticker pack")
                    pack = UserStickerPack(displayName = null, stickers = persistentListOf())
                    isLoading = false
                }
        }

        suspend fun importSticker(uri: Uri) {
            isImporting = true
            error = null
            val media = stickerMediaReader.read(uri)
            if (media == null) {
                isImporting = false
                error = StickerPickerError.Import
                return
            }
            val mimeType = media.info?.mimetype ?: "image/png"
            matrixClient.uploadMedia(mimeType = mimeType, data = media.bytes)
                .onSuccess { mxcUrl ->
                    val updated = pack.addSticker(
                        shortcode = media.filename ?: "sticker",
                        url = mxcUrl,
                        body = media.filename,
                        info = media.info,
                    )
                    matrixClient.setAccountData(USER_EMOTES_EVENT_TYPE, serializeUserStickerPack(updated))
                        .onSuccess {
                            pack = updated
                            isImporting = false
                        }
                        .onFailure {
                            Timber.e(it, "Failed to persist user sticker pack")
                            isImporting = false
                            error = StickerPickerError.Import
                        }
                }
                .onFailure {
                    Timber.e(it, "Failed to upload sticker media")
                    isImporting = false
                    error = StickerPickerError.Import
                }
        }

        val galleryImagePicker = mediaPickerProvider.registerGalleryPicker { uri, _ ->
            uri?.let { coroutineScope.launch { importSticker(it) } }
        }

        LaunchedEffect(Unit) {
            loadPack()
        }

        fun handleEvent(event: StickerPickerEvent) {
            when (event) {
                StickerPickerEvent.Dismiss -> {
                    sendResult = null
                    error = null
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
                StickerPickerEvent.ImportSticker -> {
                    error = null
                    galleryImagePicker.launch()
                }
                is StickerPickerEvent.StickerPicked -> coroutineScope.launch {
                    importSticker(event.uri)
                }
            }
        }

        return StickerPickerState(
            stickers = if (isLoading) AsyncData.Loading() else AsyncData.Success(pack.stickers),
            isImporting = isImporting,
            error = error,
            sendResult = sendResult,
            eventSink = ::handleEvent,
        )
    }

    companion object {
        private const val USER_EMOTES_EVENT_TYPE = "im.ponies.user_emotes"
    }
}