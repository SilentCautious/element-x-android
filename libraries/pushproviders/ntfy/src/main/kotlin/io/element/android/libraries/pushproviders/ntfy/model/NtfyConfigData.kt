/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.model

import io.element.android.libraries.pushproviders.ntfy.NtfyConfig
import kotlinx.serialization.Serializable

/**
 * Configuration of the ntfy topic a session receives its push notifications on.
 *
 * @property serverUrl base URL of the ntfy server.
 * @property topic the topic this session is subscribed to.
 * @property authToken optional base64 encoded `Basic user:password` credential, only needed for a protected topic.
 * @property priority priority of the notifications, see https://docs.ntfy.sh/publish/#message-priority
 * @property tags tags of the notifications, see https://docs.ntfy.sh/publish/#tags-emojis
 */
@Serializable
data class NtfyConfigData(
    val serverUrl: String = NtfyConfig.DEFAULT_SERVER_URL,
    val topic: String,
    val authToken: String? = null,
    val priority: Int = NtfyConfig.DEFAULT_PRIORITY,
    val tags: List<String> = emptyList(),
) {
    private val baseUrl: String
        get() = serverUrl.trimEnd('/')

    /**
     * URL of the topic, used as the push key when registering the pusher.
     * e.g. `https://ntfy.sh/element-abc123`.
     */
    val publishUrl: String
        get() = "$baseUrl/$topic"

    /**
     * Matrix push gateway to register the pusher with. It has to be the gateway of [serverUrl]:
     * ntfy only accepts a push key prefixed with its own base URL.
     * e.g. `https://ntfy.sh/_matrix/push/v1/notify`.
     */
    val pushGatewayUrl: String
        get() = "$baseUrl/_matrix/push/v1/notify"

    /**
     * WebSocket URL to subscribe to, always returning JSON messages.
     * e.g. `wss://ntfy.sh/element-abc123/ws`.
     *
     * @param since id of the last message already handled. When set, ntfy first replays the messages
     * published after that one, so a reconnection does not lose what happened while it was down.
     * Passing `all` would replay the whole cache and must not be used here, it would duplicate
     * notifications that have already been shown.
     *
     * @see <a href="https://docs.ntfy.sh/subscribe/api/#websockets">ntfy WebSocket documentation</a>
     */
    fun websocketUrl(since: String? = null): String {
        val queryParameters = buildList {
            since?.takeIf { it.isNotBlank() }?.let { add("since=$it") }
            authToken?.let { add("auth=$it") }
        }
        return buildString {
            append(baseUrl.toWebSocketScheme())
            append('/')
            append(topic)
            append("/ws")
            if (queryParameters.isNotEmpty()) {
                append('?')
                append(queryParameters.joinToString("&"))
            }
        }
    }
}

private fun String.toWebSocketScheme(): String = when {
    startsWith("https://") -> "wss://${removePrefix("https://")}"
    startsWith("http://") -> "ws://${removePrefix("http://")}"
    else -> this
}
