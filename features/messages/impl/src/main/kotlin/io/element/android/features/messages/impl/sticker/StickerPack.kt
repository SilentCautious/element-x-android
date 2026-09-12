/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import androidx.compose.runtime.Immutable
import io.element.android.libraries.matrix.api.media.ImageInfo
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

@Immutable
data class StickerImage(
    val shortcode: String,
    val url: String,
    val body: String?,
    val info: ImageInfo?,
)

@Immutable
data class UserStickerPack(
    val displayName: String?,
    val stickers: ImmutableList<StickerImage>,
) {
    fun addSticker(shortcode: String, url: String, body: String?, info: ImageInfo?): UserStickerPack {
        val base = sanitizeShortcode(shortcode)
        var candidate = base
        var suffix = 1
        while (stickers.any { it.shortcode == candidate }) {
            candidate = "$base-${suffix++}"
        }
        return UserStickerPack(
            displayName = displayName,
            stickers = (stickers + StickerImage(shortcode = candidate, url = url, body = body, info = info)).toPersistentList(),
        )
    }
}

fun sanitizeShortcode(raw: String): String {
    val cleaned = raw
        .substringBeforeLast('.')
        .map { c -> if (c.isLetterOrDigit() && c.code < 128) c else if (c == '-' || c == '_') c else if (c.isWhitespace()) '_' else null }
        .filterNotNull()
        .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
        .take(100)
        .joinToString("")
    return cleaned.ifEmpty { "sticker" }
}

fun parseUserStickerPack(raw: String?): UserStickerPack {
    val empty = UserStickerPack(displayName = null, stickers = persistentListOf())
    if (raw.isNullOrBlank()) return empty
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return empty
    val displayName = (root["pack"] as? JsonObject)?.get("display_name")?.let { it as? JsonPrimitive }?.content
    val stickers = (root["images"] as? JsonObject)?.entries?.mapNotNull { (shortcode, value) ->
        if (value !is JsonObject) return@mapNotNull null
        val url = (value["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        StickerImage(
            shortcode = shortcode,
            url = url,
            body = (value["body"] as? JsonPrimitive)?.content,
            info = (value["info"] as? JsonObject)?.toImageInfo(),
        )
    }.orEmpty()
    return UserStickerPack(displayName = displayName, stickers = stickers.toPersistentList())
}

fun serializeUserStickerPack(pack: UserStickerPack): String = buildJsonObject {
    put("pack", buildJsonObject {
        pack.displayName?.let { put("display_name", it) }
        put("usage", buildJsonArray { add("sticker") })
    })
    put("images", buildJsonObject {
        pack.stickers.forEach { sticker ->
            put(sticker.shortcode, buildJsonObject {
                put("url", sticker.url)
                sticker.body?.let { put("body", it) }
                sticker.info?.let { info ->
                    put("info", buildJsonObject {
                        info.width?.let { put("w", it) }
                        info.height?.let { put("h", it) }
                        info.mimetype?.let { put("mimetype", it) }
                        info.size?.let { put("size", it) }
                        info.blurhash?.let { put("blurhash", it) }
                    })
                }
            })
        }
    })
}.toString()

private fun JsonObject.toImageInfo(): ImageInfo = ImageInfo(
    height = getLong("h"),
    width = getLong("w"),
    mimetype = (get("mimetype") as? JsonPrimitive)?.content,
    size = getLong("size"),
    thumbnailInfo = null,
    thumbnailSource = null,
    blurhash = (get("blurhash") as? JsonPrimitive)?.content,
)

private fun JsonObject.getLong(key: String): Long? = (get(key) as? JsonPrimitive)?.content?.toLongOrNull()