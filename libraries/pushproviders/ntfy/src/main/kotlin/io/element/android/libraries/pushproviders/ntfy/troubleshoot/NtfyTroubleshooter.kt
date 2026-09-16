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
import io.element.android.libraries.pushproviders.ntfy.store.NtfyStore
import io.element.android.libraries.pushstore.api.clientsecret.PushClientSecret
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
        // Drop the current subscription before restarting it: it may have given up after too many failures.
        ntfyWebSocketManager.stop(clientSecret)
        ntfyWebSocketManager.start(clientSecret, config)
        Result.success(config.publishUrl)
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
}
