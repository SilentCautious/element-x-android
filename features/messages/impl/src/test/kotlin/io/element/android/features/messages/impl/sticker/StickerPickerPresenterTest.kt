/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StickerPickerPresenterTest {
    private val widgetsJson = """
        {
          "stickerpicker": {
            "content": {
              "type": "m.stickerpicker",
              "url": "https://stickers.example/web/?theme=${'$'}theme",
              "name": "Stickerpicker"
            },
            "id": "stickerpicker",
            "type": "m.widget"
          }
        }
    """.trimIndent()

    @Test
    fun `present - no stickerpicker widget shows empty stickers`() = runTest {
        createPresenter(rawWidgets = null).test {
            val state = consumeItemsUntilPredicate { it.stickers.isSuccess() }.last()
            assertThat(state.stickers.dataOrNull()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - loads stickers from the widget server`() = runTest {
        val server = FakeStickerServerClient()
        createPresenter(serverClient = server).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            assertThat(state.stickers.dataOrNull()?.first()?.shortcode).isEqualTo("cat")
            assertThat(server.lastWidgetUrl).isEqualTo("https://stickers.example/web/?theme=${'$'}theme")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - server failure reports load error`() = runTest {
        val server = FakeStickerServerClient(result = Result.failure(IllegalStateException("boom")))
        createPresenter(serverClient = server).test {
            val state = consumeItemsUntilPredicate { it.error == StickerPickerError.Load }.last()
            assertThat(state.stickers.dataOrNull()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - reload fetches the latest stickers`() = runTest {
        val server = FakeStickerServerClient()
        createPresenter(serverClient = server).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            server.result = Result.success(persistentListOf(aSticker(shortcode = "dog"), aSticker(shortcode = "moon")))
            state.eventSink(StickerPickerEvent.Reload)
            val reloaded = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 2 }.last()
            assertThat(reloaded.stickers.dataOrNull()?.map { it.shortcode }).containsExactly("dog", "moon").inOrder()
            assertThat(server.fetchCallCount).isEqualTo(2)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - selecting a sticker sends it and reports success`() = runTest {
        val room = FakeJoinedRoom()
        createPresenter(room = room).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            state.eventSink(StickerPickerEvent.SelectSticker(state.stickers.dataOrNull()!!.first()))
            val sent = consumeItemsUntilPredicate { it.sendResult == true }.last()
            assertThat(room.sentStickers).hasSize(1)
            assertThat(room.sentStickers.first().first).isEqualTo("mxc://example.org/cat")
            assertThat(room.sentStickers.first().second).isEqualTo("a cat")
            assertThat(sent.sendResult).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - send failure reports failure and keeps stickers`() = runTest {
        val room = FakeJoinedRoom().apply { givenSendStickerResult(Result.failure(IllegalStateException("boom"))) }
        createPresenter(room = room).test {
            val state = consumeItemsUntilPredicate { it.stickers.dataOrNull()?.size == 1 }.last()
            state.eventSink(StickerPickerEvent.SelectSticker(state.stickers.dataOrNull()!!.first()))
            val failed = consumeItemsUntilPredicate { it.sendResult == false }.last()
            assertThat(failed.error).isEqualTo(StickerPickerError.Send)
            assertThat(failed.stickers.dataOrNull()).hasSize(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createPresenter(
        room: FakeJoinedRoom = FakeJoinedRoom(),
        rawWidgets: String? = widgetsJson,
        serverClient: StickerServerClient = FakeStickerServerClient(),
    ) = StickerPickerPresenter(
        room = room,
        matrixClient = FakeMatrixClient().apply {
            getAccountDataLambda = { eventType ->
                Result.success(if (eventType == StickerPickerPresenter.WIDGETS_EVENT_TYPE) rawWidgets else null)
            }
        },
        stickerServerClient = serverClient,
    )
}

private class FakeStickerServerClient(
    var result: Result<ImmutableList<StickerImage>> = Result.success(persistentListOf(aSticker())),
) : StickerServerClient {
    var lastWidgetUrl: String? = null
        private set
    var fetchCallCount: Int = 0
        private set

    override suspend fun fetchStickerPacks(widgetUrl: String): Result<ImmutableList<StickerImage>> {
        lastWidgetUrl = widgetUrl
        fetchCallCount++
        return result
    }
}

private fun aSticker(shortcode: String = "cat") = StickerImage(
    shortcode = shortcode,
    url = "mxc://example.org/cat",
    body = "a cat",
    info = null,
)
