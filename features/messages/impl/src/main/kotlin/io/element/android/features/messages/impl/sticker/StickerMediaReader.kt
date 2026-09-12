/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.element.android.libraries.androidutils.file.getFileName
import io.element.android.libraries.androidutils.file.getMimeType
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.media.ImageInfo
import kotlinx.coroutines.withContext

interface StickerMediaReader {
    data class StickerMedia(val bytes: ByteArray, val info: ImageInfo?, val filename: String?)

    suspend fun read(uri: Uri): StickerMedia?
}

@ContributesBinding(AppScope::class)
@Inject
class DefaultStickerMediaReader(
    private val coroutineDispatchers: CoroutineDispatchers,
    @ApplicationContext private val context: Context,
) : StickerMediaReader {

    override suspend fun read(uri: Uri): StickerMediaReader.StickerMedia? = withContext(coroutineDispatchers.io) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val info = ImageInfo(
            width = bounds.outWidth.takeIf { it > 0 }?.toLong(),
            height = bounds.outHeight.takeIf { it > 0 }?.toLong(),
            mimetype = context.getMimeType(uri) ?: bounds.outMimeType,
            size = bytes.size.toLong(),
            thumbnailInfo = null,
            thumbnailSource = null,
            blurhash = null,
        )
        StickerMediaReader.StickerMedia(bytes = bytes, info = info, filename = context.getFileName(uri))
    }
}