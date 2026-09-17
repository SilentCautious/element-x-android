/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.troubleshoot

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.pushproviders.ntfy.NtfyWebSocketManager
import io.element.android.libraries.pushproviders.ntfy.model.NtfyConfigData
import io.element.android.libraries.pushproviders.ntfy.store.NtfyStore
import io.element.android.libraries.pushstore.api.clientsecret.PushClientSecret
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

private val loggerTag = LoggerTag("NtfyTroubleshooter", LoggerTag.PushLoggerTag)

interface NtfyTroubleshooter {
    /**
     * Checks that the ntfy server answers for the topic of the session, and re-creates the
     * subscription, since the client gives up for good after too many failed reconnections.
     *
     * @return the url of the topic, to be shown to the user.
     */
    suspend fun troubleshoot(sessionId: SessionId): Result<String>
}

@ContributesBinding(AppScope::class)
class DefaultNtfyTroubleshooter(
    private val okHttpClient: OkHttpClient,
    private val pushClientSecret: PushClientSecret,
    private val ntfyStore: NtfyStore,
    private val ntfyWebSocketManager: NtfyWebSocketManager,
    private val coroutineDispatchers: CoroutineDispatchers,
) : NtfyTroubleshooter {
    override suspend fun troubleshoot(sessionId: SessionId): Result<String> = withContext(coroutineDispatchers.io) {
        val clientSecret = pushClientSecret.getSecretForUser(sessionId)
        val config = ntfyStore.getConfig(clientSecret)
            ?: return@withContext Result.failure(IllegalStateException("This session is not registered with ntfy"))
        if (!isTopicReachable(config.publishUrl)) {
            return@withContext Result.failure(IllegalStateException("The ntfy server did not answer for the topic `${config.topic}`"))
        }
        // Only rebuild the subscription when it is not live: restarting a healthy one would reset
        // the diagnostics, which are exactly what is being looked at here.
        if (!ntfyWebSocketManager.isConnected(clientSecret)) {
            ntfyWebSocketManager.stop(clientSecret)
            ntfyWebSocketManager.start(clientSecret, config)
        }
        // The connection is asynchronous: wait for it so that the result reflects reality rather
        // than merely reporting that the subscription was asked for.
        repeat(CONNECTION_POLL_ATTEMPTS) {
            delay(CONNECTION_POLL_DELAY_MS)
            if (ntfyWebSocketManager.isConnected(clientSecret)) {
                return@withContext Result.success(reportTopicState(config, clientSecret))
            }
        }
        return@withContext Result.failure(
            IllegalStateException("The topic is reachable but the WebSocket is not connected to `${config.topic}`")
        )
    }

    /**
     * Reports the topic and what the subscription has received, since the app logs do not reach
     * logcat and this is the only place a failure can be observed.
     */
    private fun reportTopicState(config: NtfyConfigData, clientSecret: String): String {
        return buildString {
            append(config.publishUrl)
            val diagnostics = ntfyWebSocketManager.diagnostics(clientSecret)
            if (diagnostics != null) {
                append("\nFrames received: ").append(diagnostics.receivedFrameCount)
                diagnostics.lastFrameEvent?.let { append("\nLast event: ").append(it) }
                diagnostics.lastHandlingOutcome?.let { append("\nLast handling: ").append(it) }
                diagnostics.lastFailure?.let { append("\nLast failure: ").append(it) }
            }
        }
    }

    private fun isTopicReachable(publishUrl: String): Boolean {
        val request = Request.Builder()
            .url("$publishUrl/json?poll=1")
            .build()
        return tryOrNull(
            onException = { Timber.tag(loggerTag.value).w(it, "Unable to reach the ntfy server") }
        ) {
            okHttpClient.newCall(request).execute().use { it.isSuccessful }
        } ?: false
    }

    private companion object {
        private const val CONNECTION_POLL_DELAY_MS = 500L
        private const val CONNECTION_POLL_ATTEMPTS = 10
    }
}
