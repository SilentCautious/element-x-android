/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.model

import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.pushproviders.api.PushData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Body of the POST request the push gateway sends to the ntfy topic, which ntfy then stores verbatim
 * in `NtfyMessage.message`.
 *
 * This is the Matrix push gateway format, the same one the homeserver sends to the gateway:
 * <pre>
 * {
 *     "notification": {
 *         "event_id": "$anEventId",
 *         "room_id": "!aRoomId",
 *         "counts": { "unread": 1 },
 *         "prio": "high",
 *         "devices": [ { "app_id": "...", "pushkey": "...", "data": { "cs": "aSecret" } } ]
 *     }
 * }
 * </pre>
 *
 * @see <a href="https://spec.matrix.org/latest/push-gateway-api/">Matrix Push Gateway API</a>
 */
@Serializable
data class NtfyNotificationPayload(
    @SerialName("notification") val notification: NtfyNotification? = null,
)

@Serializable
data class NtfyNotification(
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("counts") val counts: NtfyCounts? = null,
    @SerialName("prio") val prio: String? = null,
    @SerialName("devices") val devices: List<NtfyDevice> = emptyList(),
)

@Serializable
data class NtfyCounts(
    @SerialName("unread") val unread: Int? = null,
    @SerialName("missed_calls") val missedCalls: Int? = null,
)

@Serializable
data class NtfyDevice(
    @SerialName("app_id") val appId: String? = null,
    @SerialName("pushkey") val pushKey: String? = null,
    /** The data set when the pusher was registered, in particular the client secret under the `cs` key. */
    @SerialName("data") val data: Map<String, String> = emptyMap(),
)

/**
 * Decodes the notification carried by a ntfy envelope and converts it into the [PushData] consumed
 * by the push pipeline.
 *
 * @param json the json decoder to use, since ntfy stores the body as a string in the `message` field.
 * @param clientSecret identifies the session the notification belongs to. It is provided by the
 * subscription itself rather than read from `notification.devices[].data.cs`: that payload is only
 * filled with a placeholder secret when the "test push" of the troubleshooting screen is used, and
 * whether a gateway relays `devices` at all is not guaranteed.
 * @return `null` when the envelope is not a notification, or does not carry what the pipeline needs.
 */
fun NtfyMessage.toPushData(json: Json, clientSecret: String): PushData? {
    if (!isMessage) return null
    val body = message?.takeIf { it.isNotBlank() } ?: return null
    val payload = tryOrNull { json.decodeFromString(NtfyNotificationPayload.serializer(), body) } ?: return null
    val notification = payload.notification ?: return null
    val safeEventId = notification.eventId?.let { EventId(it) } ?: return null
    val safeRoomId = notification.roomId?.let { RoomId(it) } ?: return null
    return PushData(
        eventId = safeEventId,
        roomId = safeRoomId,
        unread = notification.counts?.unread,
        clientSecret = clientSecret,
    )
}
