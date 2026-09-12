/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.media.ImageInfo
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class StickerPackTest {
    private val packJson = """
        {
          "pack": { "display_name": "My stickers", "usage": ["sticker"] },
          "images": {
            "cat": {
              "url": "mxc://example.org/cat",
              "body": "a cat",
              "info": { "w": 512, "h": 512, "mimetype": "image/png", "size": 12345 }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parse - null or blank account data yields empty pack`() {
        assertThat(parseUserStickerPack(null))
            .isEqualTo(UserStickerPack(displayName = null, stickers = persistentListOf<StickerImage>()))
        assertThat(parseUserStickerPack(""))
            .isEqualTo(UserStickerPack(displayName = null, stickers = persistentListOf<StickerImage>()))
        assertThat(parseUserStickerPack("  "))
            .isEqualTo(UserStickerPack(displayName = null, stickers = persistentListOf<StickerImage>()))
    }

    @Test
    fun `parse - malformed json yields empty pack`() {
        assertThat(parseUserStickerPack("not json").stickers).isEmpty()
    }

    @Test
    fun `parse - full pack maps all fields`() {
        val pack = parseUserStickerPack(packJson)
        assertThat(pack.displayName).isEqualTo("My stickers")
        assertThat(pack.stickers).hasSize(1)
        val sticker = pack.stickers.first()
        assertThat(sticker.shortcode).isEqualTo("cat")
        assertThat(sticker.url).isEqualTo("mxc://example.org/cat")
        assertThat(sticker.body).isEqualTo("a cat")
        assertThat(sticker.info).isEqualTo(
            ImageInfo(width = 512L, height = 512L, mimetype = "image/png", size = 12345L, thumbnailInfo = null, thumbnailSource = null, blurhash = null)
        )
    }

    @Test
    fun `parse - entries without url are skipped`() {
        val json = """{"images": {"bad": {"body": "no url"}, "good": {"url": "mxc://e.org/good"}}}"""
        val pack = parseUserStickerPack(json)
        assertThat(pack.stickers.map { it.shortcode }).containsExactly("good")
    }

    @Test
    fun `parse - compound values where primitives are expected degrade gracefully`() {
        val json = """{"pack": {"display_name": {}}, "images": {"obj": {"url": {}, "body": [], "info": "nope"}, "num": {"url": "mxc://e.org/num", "info": {"w": {}, "size": []}}, "good": {"url": "mxc://e.org/good"}}}"""
        val pack = parseUserStickerPack(json)
        assertThat(pack.displayName).isNull()
        assertThat(pack.stickers.map { it.shortcode }).containsExactly("num", "good").inOrder()
        val num = pack.stickers.first()
        assertThat(num.body).isNull()
        assertThat(num.info).isEqualTo(
            ImageInfo(width = null, height = null, mimetype = null, size = null, thumbnailInfo = null, thumbnailSource = null, blurhash = null)
        )
    }

    @Test
    fun `serialize - round trip preserves stickers`() {
        val pack = parseUserStickerPack(packJson)
        val reparsed = parseUserStickerPack(serializeUserStickerPack(pack))
        assertThat(reparsed.stickers).isEqualTo(pack.stickers)
        assertThat(reparsed.displayName).isEqualTo(pack.displayName)
    }

    @Test
    fun `serialize - pack usage is sticker`() {
        val json = serializeUserStickerPack(parseUserStickerPack(packJson))
        assertThat(json).contains("\"usage\"")
        assertThat(json).contains("\"sticker\"")
    }

    @Test
    fun `add - appends sticker and deduplicates shortcodes`() {
        val pack = parseUserStickerPack(packJson)
        val added = pack.addSticker(shortcode = "cat", url = "mxc://example.org/cat2", body = "another cat", info = null)
        assertThat(added.stickers.map { it.shortcode }).containsExactly("cat", "cat-1").inOrder()
        val addedAgain = added.addSticker(shortcode = "cat-1", url = "mxc://example.org/x", body = null, info = null)
        assertThat(addedAgain.stickers.map { it.shortcode }).containsExactly("cat", "cat-1", "cat-1-1").inOrder()
    }

    @Test
    fun `sanitize - enforces shortcode grammar with fallback`() {
        assertThat(sanitizeShortcode("My Photo (1).PNG")).isEqualTo("My_Photo_1")
        assertThat(sanitizeShortcode("照片")).isEqualTo("sticker")
        assertThat(sanitizeShortcode("a")).isEqualTo("a")
    }
}