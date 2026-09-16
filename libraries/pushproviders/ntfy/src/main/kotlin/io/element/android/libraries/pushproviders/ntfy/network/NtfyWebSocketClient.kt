/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.network

import io.element.android.libraries.androidutils.json.JsonProvider
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.pushproviders.ntfy.NtfyConfig
import io.element.android.libraries.pushproviders.ntfy.model.NtfyConfigData
import io.element.android.libraries.pushproviders.ntfy.model.NtfyMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val loggerTag = LoggerTag("NtfyWebSocketClient")

/**
 * Subscribes to a ntfy topic through a WebSocket, to receive the push notifications of a session.
 *
 * The connection is kept alive by the ping frames sent by OkHttp, and automatically restored with an
 * exponential backoff when it is lost. [connect] starts the subscription, [disconnect] stops it for good.
 *
 * @see <a href="https://docs.ntfy.sh/subscribe/api/#websockets">ntfy WebSocket documentation</a>
 */
class NtfyWebSocketClient(
    okHttpClient: OkHttpClient,
    private val jsonProvider: JsonProvider,
    private val scope: CoroutineScope,
    private val config: NtfyConfigData,
    /** Id of the last message already handled, used to catch up on the messages missed while down. */
    private val lastMessageIdProvider: () -> String?,
    private val onMessage: (NtfyMessage) -> Unit,
    private val onConnectionChange: (Boolean) -> Unit,
) {
    /**
     * Dedicated client: the ping interval keeps the connection alive on a quiet topic, and makes
     * OkHttp fail the socket when the peer does not answer, which triggers the reconnection.
     */
    private val socketClient = okHttpClient.newBuilder()
        .pingInterval(NtfyConfig.HEARTBEAT_INTERVAL_SECONDS.seconds)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0
    private var isShuttingDown: Boolean = false

    /** Opens the connection, or does nothing when it is already opened. */
    fun connect() {
        if (webSocket != null) return
        isShuttingDown = false
        reconnectAttempts = 0
        openSocket()
    }

    /** Closes the connection and cancels any pending reconnection. */
    fun disconnect() {
        isShuttingDown = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(NORMAL_CLOSURE_STATUS, null)
        webSocket = null
        onConnectionChange(false)
    }

    private fun openSocket() {
        if (isShuttingDown) return
        val url = config.websocketUrl(since = lastMessageIdProvider())
        val request = Request.Builder()
            .url(url)
            .build()
        Timber.tag(loggerTag.value).d("Opening the WebSocket of the topic `${config.topic}`")
        webSocket = socketClient.newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (isShuttingDown) return
        if (reconnectAttempts >= NtfyConfig.MAX_RECONNECT_ATTEMPTS) {
            Timber.tag(loggerTag.value).w("Giving up, the connection could not be restored after $reconnectAttempts attempts")
            return
        }
        reconnectAttempts += 1
        val reconnectDelay = reconnectDelay()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Timber.tag(loggerTag.value).d("Reconnecting in ${reconnectDelay.inWholeSeconds}s (attempt $reconnectAttempts)")
            delay(reconnectDelay)
            openSocket()
        }
    }

    /** Exponential backoff: 5s, 10s, 20s... capped to [NtfyConfig.MAX_RECONNECT_DELAY_SECONDS]. */
    private fun reconnectDelay(): Duration {
        val exponent = (reconnectAttempts - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        val delaySeconds = NtfyConfig.BASE_RECONNECT_DELAY_SECONDS * (1L shl exponent)
        return delaySeconds.coerceAtMost(NtfyConfig.MAX_RECONNECT_DELAY_SECONDS).seconds
    }

    private fun handleTextMessage(text: String) {
        val message = tryOrNull(
            onException = { Timber.tag(loggerTag.value).w(it, "Unable to parse the incoming payload") }
        ) {
            jsonProvider().decodeFromString(NtfyMessage.serializer(), text)
        } ?: return
        if (message.isMessage) {
            onMessage(message)
        } else {
            Timber.tag(loggerTag.value).v("Ignoring the `${message.event}` event")
        }
    }

    private fun onSocketDown() {
        webSocket = null
        onConnectionChange(false)
        scheduleReconnect()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.tag(loggerTag.value).d("WebSocket opened")
            reconnectAttempts = 0
            onConnectionChange(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleTextMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Timber.tag(loggerTag.value).w("Ignoring a binary frame of ${bytes.size} bytes")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(loggerTag.value).d("WebSocket closing, code=$code")
            webSocket.close(NORMAL_CLOSURE_STATUS, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.tag(loggerTag.value).d("WebSocket closed, code=$code")
            this@NtfyWebSocketClient.onSocketDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.tag(loggerTag.value).w(t, "WebSocket failure, httpCode=${response?.code}")
            this@NtfyWebSocketClient.onSocketDown()
        }
    }

    companion object {
        /** 1000 is the normal closure status code, see RFC 6455 section 7.4.1. */
        private const val NORMAL_CLOSURE_STATUS = 1000

        /** 8 gives a 5s * 2^8 = 1280s delay, capped by [NtfyConfig.MAX_RECONNECT_DELAY_SECONDS]. */
        private const val MAX_BACKOFF_EXPONENT = 8
    }
}
