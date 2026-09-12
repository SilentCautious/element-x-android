/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.matrix.api.media.ImageInfo
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StickerPickerPresenterTest {
    private val packJson =
        """{"pack":{"display_name":"My stickers","usage":["sticker"]},"images":{"cat":{"url":"mxc://e.org/cat","body":"a cat","info":{"w":512,"h":512,"mimetype":"image/png","size":1}}}}"""

    private fun aMedia(filename: String = "cat.png") = StickerMediaReader.StickerMedia(
        bytes = ByteArray(1),
        info = ImageInfo(width = 512L, height = 512L, mimetype = "image/png", size = 1L, thumbnailInfo = null, thumbnailSource = null, blurhash = null),
        filename = filename,
    )

    @Test
    fun `present - empty account data shows empty stickers`() = runTest {
        createPresenter().test {
            val state = consumeItemsUntilPredicate { it.stickers.isSuccess() }.last()
            assertThat(state.stickers.dataOrNull()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - pack loads from account data`() = runTest {
        createPresenter(accountData = packJson).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            assertThat(state.stickers.dataOrNull()!!.first().shortcode).isEqualTo("cat")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - selecting a sticker sends it and reports success`() = runTest {
        val room = FakeJoinedRoom()
        createPresenter(accountData = packJson, room = room).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            state.eventSink(StickerPickerEvent.SelectSticker(state.stickers.dataOrNull()!!.first()))
            val sent = consumeItemsUntilPredicate { it.sendResult == true }.last()
            assertThat(room.sentStickers).hasSize(1)
            assertThat(room.sentStickers.first().first).isEqualTo("mxc://e.org/cat")
            assertThat(room.sentStickers.first().second).isEqualTo("a cat")
            assertThat(sent.sendResult).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send failure reports failure and keeps stickers`() = runTest {
        val room = FakeJoinedRoom().apply { givenSendStickerResult(Result.failure(IllegalStateException("boom"))) }
        createPresenter(accountData = packJson, room = room).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            state.eventSink(StickerPickerEvent.SelectSticker(state.stickers.dataOrNull()!!.first()))
            val failed = consumeItemsUntilPredicate { it.sendResult == false }.last()
            assertThat(failed.error).isEqualTo(StickerPickerError.Send)
            assertThat(failed.stickers.dataOrNull()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - picking a media uploads it and persists into the pack`() = runTest {
        val client = FakeMatrixClient().apply {
            getAccountDataLambda = { Result.success(null) }
            setAccountDataLambda = { _, content -> savedAccountData = content; Result.success(Unit) }
        }
        createPresenter(matrixClient = client, mediaReader = FakeStickerMediaReader(aMedia())).test {
            val state = consumeItemsUntilPredicate { it.stickers.isSuccess() }.last()
            state.eventSink(
                StickerPickerEvent.StickerPicked(
                    uri = mockk(),
                    mimeType = "image/png",
                    filename = "cat.png",
                )
            )
            val latest = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 && !it.isImporting }.last()
            val added = latest.stickers.dataOrNull()!!.first()
            assertThat(added.shortcode).isEqualTo("cat")
            assertThat(added.url).isNotEmpty()
            assertThat(client.savedAccountData).contains("\"cat\"")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - upload failure sets import error and does not persist`() = runTest {
        val client = FakeMatrixClient().apply {
            getAccountDataLambda = { Result.success(null) }
            givenUploadMediaResult(Result.failure(IllegalStateException("boom")))
            setAccountDataLambda = { _, content -> savedAccountData = content; Result.success(Unit) }
        }
        createPresenter(matrixClient = client, mediaReader = FakeStickerMediaReader(aMedia())).test {
            val state = consumeItemsUntilPredicate { it.stickers.isSuccess() }.last()
            state.eventSink(
                StickerPickerEvent.StickerPicked(
                    uri = mockk(),
                    mimeType = "image/png",
                    filename = "cat.png",
                )
            )
            val latest = consumeItemsUntilPredicate { it.error == StickerPickerError.Import }.last()
            assertThat(latest.stickers.dataOrNull()).isEmpty()
            assertThat(client.savedAccountData).isNull()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.createPresenter(
        accountData: String? = null,
        room: FakeJoinedRoom = FakeJoinedRoom(),
        matrixClient: FakeMatrixClient = FakeMatrixClient().apply {
            getAccountDataLambda = { Result.success(accountData) }
            setAccountDataLambda = { _, _ -> Result.success(Unit) }
        },
        mediaReader: StickerMediaReader = FakeStickerMediaReader(),
    ) = StickerPickerPresenter(
        room = room,
        matrixClient = matrixClient,
        mediaPickerProvider = FakePickerProvider(),
        stickerMediaReader = mediaReader,
        coroutineDispatchers = testCoroutineDispatchers(),
    )
}

private class FakeStickerMediaReader(var result: StickerMediaReader.StickerMedia? = null) : StickerMediaReader {
    override suspend fun read(uri: android.net.Uri): StickerMediaReader.StickerMedia? = result
}
