/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Envelope of a message received from the ntfy WebSocket endpoint, which always returns JSON objects.
 *
 * Note: when a body is published to `https://<server>/<topic>`, ntfy does **not** parse it, and stores
 * it verbatim in [message]. The Matrix notification therefore has to be decoded from [message] itself,
 * see [toPushData]. This has been verified against ntfy.sh, see `spike/ntfy-json-semantics.py`.
 *
 * @see <a href="https://docs.ntfy.sh/subscribe/api/#json-message-format">ntfy JSON message format</a>
 */
@Serializable
data class NtfyMessage(
    @SerialName("id") val id: String,
    @SerialName("time") val time: Long,
    @SerialName("event") val event: String = EVENT_MESSAGE,
    @SerialName("topic") val topic: String,
    @SerialName("message") val message: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("priority") val priority: Int? = null,
    @SerialName("click") val clickUrl: String? = null,
    @SerialName("actions") val actions: List<NtfyAction> = emptyList(),
    @SerialName("attach") val attachmentUrl: String? = null,
    @SerialName("filename") val filename: String? = null,
    @SerialName("expiry") val expiry: Long? = null,
) {
    /** `true` when the envelope carries an actual notification, and not a `open` or `keepalive` event. */
    val isMessage: Boolean
        get() = event == EVENT_MESSAGE

    companion object {
        /** Value of the `event` field for an actual notification. */
        const val EVENT_MESSAGE = "message"
    }
}

@Serializable
data class NtfyAction(
    @SerialName("action") val action: String,
    @SerialName("label") val label: String,
    @SerialName("url") val url: String? = null,
    @SerialName("clear") val clear: Boolean = false,
)
