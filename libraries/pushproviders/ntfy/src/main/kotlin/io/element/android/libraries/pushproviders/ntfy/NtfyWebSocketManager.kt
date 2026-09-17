/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.androidutils.json.JsonProvider
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.pushproviders.api.PushHandler
import io.element.android.libraries.pushproviders.ntfy.model.NtfyConfigData
import io.element.android.libraries.pushproviders.ntfy.model.NtfyMessage
import io.element.android.libraries.pushproviders.ntfy.model.toPushData
import io.element.android.libraries.pushproviders.ntfy.network.NtfySubscriptionDiagnostics
import io.element.android.libraries.pushproviders.ntfy.network.NtfyWebSocketClient
import io.element.android.libraries.pushproviders.ntfy.store.NtfyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

private val loggerTag = LoggerTag("NtfyWebSocketManager", LoggerTag.PushLoggerTag)

/**
 * Holds one ntfy subscription per session and feeds the received notifications to the push pipeline.
 *
 * A subscription only lives while the app process does: unlike Firebase, ntfy cannot wake the app up,
 * so nothing is received once the process is killed. Keeping it alive in the background requires a
 * long running foreground service, see the notes in the repository about ntfy.
 */
interface NtfyWebSocketManager {
    /** Subscribes the session to its topic, or does nothing when it is already subscribed. */
    fun start(clientSecret: String, config: NtfyConfigData)

    /** Cancels the subscription of the session. */
    fun stop(clientSecret: String)

    /** Cancels every subscription, to be used when the user signs out. */
    fun stopAll()

    /**
     * Whether the subscription of the session is currently established.
     *
     * [start] only begins the connection, which happens asynchronously and may never succeed: this
     * is how the troubleshooting screen tells a live subscription from one that keeps failing.
     */
    fun isConnected(clientSecret: String): Boolean

    /** Whether at least one session is still subscribed, to know when the keep alive service can be stopped. */
    fun hasSubscriptions(): Boolean

    /** What the subscription of the session has seen so far, or `null` when it is not subscribed. */
    fun diagnostics(clientSecret: String): NtfySubscriptionDiagnostics?
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class DefaultNtfyWebSocketManager(
    private val okHttpClient: OkHttpClient,
    private val jsonProvider: JsonProvider,
    private val pushHandler: PushHandler,
    private val ntfyStore: NtfyStore,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : NtfyWebSocketManager {
    private val clients = ConcurrentHashMap<String, NtfyWebSocketClient>()

    /**
     * Result of the last frame handled per session. Kept here rather than in the client because the
     * conversion and the hand over to the push pipeline happen here, and it has to be visible from
     * the troubleshooting screen since the app logs do not reach logcat.
     */
    private val lastHandlingOutcomes = ConcurrentHashMap<String, String>()

    override fun start(clientSecret: String, config: NtfyConfigData) {
        if (clients.containsKey(clientSecret)) {
            Timber.tag(loggerTag.value).d("Already subscribed to the topic `${config.topic}`")
            return
        }
        val client = NtfyWebSocketClient(
            okHttpClient = okHttpClient,
            jsonProvider = jsonProvider,
            scope = appCoroutineScope,
            config = config,
            lastMessageIdProvider = { ntfyStore.getLastMessageId(clientSecret) },
            onMessage = { message -> appCoroutineScope.launch { handleMessage(clientSecret, message) } },
            onConnectionChange = { isConnected ->
                Timber.tag(loggerTag.value).d("Connection of the topic `${config.topic}` is $isConnected")
            },
        )
        clients[clientSecret] = client
        client.connect()
    }

    override fun stop(clientSecret: String) {
        clients.remove(clientSecret)?.disconnect()
    }

    override fun stopAll() {
        clients.values.forEach { it.disconnect() }
        clients.clear()
    }

    override fun isConnected(clientSecret: String): Boolean = clients[clientSecret]?.isConnected ?: false

    override fun hasSubscriptions(): Boolean = clients.isNotEmpty()

    override fun diagnostics(clientSecret: String): NtfySubscriptionDiagnostics? {
        val client = clients[clientSecret] ?: return null
        return client.diagnostics.copy(lastHandlingOutcome = lastHandlingOutcomes[clientSecret])
    }

    private suspend fun handleMessage(clientSecret: String, message: NtfyMessage) {
        val providerInfo = "${NtfyConfig.NAME} - $clientSecret"
        Timber.tag(loggerTag.value).d("Received a `${message.event}` event on the topic `${message.topic}`")
        try {
            // The session comes from the subscription this message arrived on, not from the payload.
            val pushData = message.toPushData(json = jsonProvider(), clientSecret = clientSecret)
            if (pushData == null) {
                lastHandlingOutcomes[clientSecret] = "decode failed (message field could not be turned into a push)"
                Timber.tag(loggerTag.value).w("Unable to decode the payload of the message `${message.id}`")
                pushHandler.handleInvalid(
                    providerInfo = providerInfo,
                    data = message.message.orEmpty(),
                )
                return
            }
            val handled = pushHandler.handle(
                pushData = pushData,
                providerInfo = providerInfo,
            )
            lastHandlingOutcomes[clientSecret] = "handled: $handled (event ${pushData.eventId.value})"
            Timber.tag(loggerTag.value).d("Message `${message.id}` handled: $handled")
            if (handled) {
                // Only move the cursor forward once the push has been accepted, so that a failure
                // does not silently skip the notification on the next reconnection.
                ntfyStore.storeLastMessageId(clientSecret, message.id)
            }
        } catch (throwable: Exception) {
            // Without this the coroutine would swallow the failure and the message would be lost silently.
            lastHandlingOutcomes[clientSecret] = "threw ${throwable.javaClass.simpleName}: ${throwable.message}"
            Timber.tag(loggerTag.value).e(throwable, "Unable to handle the message `${message.id}`")
        }
    }
}
