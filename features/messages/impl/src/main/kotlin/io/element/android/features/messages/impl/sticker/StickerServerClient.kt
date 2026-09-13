/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.di.RoomScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

interface StickerServerClient {
    suspend fun fetchStickerPacks(widgetUrl: String): Result<ImmutableList<StickerImage>>
}

@ContributesBinding(RoomScope::class)
class DefaultStickerServerClient(
    private val coroutineDispatchers: CoroutineDispatchers,
) : StickerServerClient {
    override suspend fun fetchStickerPacks(widgetUrl: String): Result<ImmutableList<StickerImage>> = withContext(coroutineDispatchers.io) {
        runCatching {
            val indexUrl = resolveStickerIndexUrl(widgetUrl) ?: error("Invalid sticker picker URL")
            val indexJson = fetchText(indexUrl).getOrThrow()
            val packFiles = parseStickerIndexPackFiles(indexJson)
            val indexUri = URI(indexUrl)
            packFiles.flatMap { packFile ->
                val packUrl = indexUri.resolve(packFile).toString()
                fetchText(packUrl).getOrNull()?.let(::parseServerStickerPack).orEmpty()
            }.distinctBy { it.shortcode }.toImmutableList()
        }
    }

    private fun fetchText(url: String): Result<String> = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        try {
            val responseCode = connection.responseCode
            check(responseCode in 200..299) { "Sticker server returned HTTP $responseCode" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 20_000
    }
}

internal fun resolveStickerIndexUrl(widgetUrl: String): String? = runCatching {
    val widgetUri = URI(widgetUrl)
    if (widgetUri.scheme !in setOf("http", "https") || widgetUri.host.isNullOrBlank()) return null
    val configUrl = widgetUri.queryParameter("config")
    if (!configUrl.isNullOrBlank()) {
        return widgetUri.resolve(configUrl).toString()
    }
    val widgetBaseUri = widgetUri.asDirectoryUri()
    widgetBaseUri.resolve("packs/index.json").toString()
}.getOrNull()

private fun URI.asDirectoryUri(): URI {
    val currentPath = path.orEmpty()
    val directoryPath = if (currentPath.endsWith('/')) currentPath else currentPath.substringBeforeLast('/', "") + "/"
    return URI(scheme, authority, directoryPath, null, null)
}

private fun URI.queryParameter(name: String): String? {
    val encodedValue = rawQuery
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?: return null
    return URLDecoder.decode(encodedValue.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}
