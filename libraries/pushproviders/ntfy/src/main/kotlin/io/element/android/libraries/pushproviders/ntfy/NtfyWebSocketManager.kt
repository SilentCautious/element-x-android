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

    private suspend fun handleMessage(clientSecret: String, message: NtfyMessage) {
        val providerInfo = "${NtfyConfig.NAME} - $clientSecret"
        val pushData = message.toPushData(jsonProvider())
        if (pushData == null) {
            Timber.tag(loggerTag.value).w("Invalid data received from ntfy")
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
        if (handled) {
            // Only move the cursor forward once the push has been accepted, so that a failure
            // does not silently skip the notification on the next reconnection.
            ntfyStore.storeLastMessageId(clientSecret, message.id)
        }
    }
}
