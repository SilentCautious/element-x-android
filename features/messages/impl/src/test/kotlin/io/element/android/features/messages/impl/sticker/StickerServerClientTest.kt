/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StickerServerClientTest {
    @Test
    fun `extract widget url reads the stickerpicker widget content`() {
        val raw = """
            {
              "other": {
                "content": { "type": "m.custom", "url": "https://example.org/other" }
              },
              "stickerpicker": {
                "content": {
                  "type": "m.stickerpicker",
                  "url": "https://stickers.example/web/?theme=${'$'}theme"
                },
                "type": "m.widget",
                "id": "stickerpicker"
              }
            }
        """.trimIndent()

        assertThat(extractStickerPickerWidgetUrl(raw)).isEqualTo("https://stickers.example/web/?theme=${'$'}theme")
    }

    @Test
    fun `resolve index uses packs index relative to the widget page`() {
        assertThat(resolveStickerIndexUrl("https://stickers.example/web/?theme=dark"))
            .isEqualTo("https://stickers.example/web/packs/index.json")
    }

    @Test
    fun `resolve index honors an explicit config query parameter`() {
        assertThat(resolveStickerIndexUrl("https://stickers.example/web/?config=https%3A%2F%2Fpacks.example%2Findex.json"))
            .isEqualTo("https://packs.example/index.json")
    }

    @Test
    fun `parse index extracts pack files`() {
        val raw = """{"packs":["pusheen.json","https://packs.example/rabbit.json",42]}"""
        assertThat(parseStickerIndexPackFiles(raw)).containsExactly("pusheen.json", "https://packs.example/rabbit.json").inOrder()
    }

    @Test
    fun `parse server pack maps stickers from the widget protocol`() {
        val raw = """
            {
              "title": "Pusheen",
              "id": "pack-1",
              "stickers": [
                {
                  "id": "cat",
                  "body": "a cat",
                  "url": "mxc://example.org/cat",
                  "info": { "w": 256, "h": 128, "mimetype": "image/png", "size": 42 }
                }
              ]
            }
        """.trimIndent()

        val sticker = parseServerStickerPack(raw).single()
        assertThat(sticker.shortcode).isEqualTo("pack-1-cat")
        assertThat(sticker.body).isEqualTo("a cat")
        assertThat(sticker.url).isEqualTo("mxc://example.org/cat")
        assertThat(sticker.info?.width).isEqualTo(256L)
        assertThat(sticker.info?.height).isEqualTo(128L)
    }
}
