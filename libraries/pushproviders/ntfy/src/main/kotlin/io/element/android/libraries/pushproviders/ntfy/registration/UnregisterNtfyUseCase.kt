/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.registration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.pushproviders.api.PusherSubscriber
import io.element.android.libraries.pushproviders.ntfy.NtfyWebSocketManager
import io.element.android.libraries.pushproviders.ntfy.store.NtfyStore
import timber.log.Timber

private val loggerTag = LoggerTag("UnregisterNtfyUseCase", LoggerTag.PushLoggerTag)

interface UnregisterNtfyUseCase {
    /** Removes the pusher from the homeserver, then stops the subscription and cleans up. */
    suspend fun unregister(matrixClient: MatrixClient, clientSecret: String): Result<Unit>

    /** Stops the subscription and removes everything stored for the session. */
    fun cleanup(clientSecret: String)
}

@ContributesBinding(AppScope::class)
class DefaultUnregisterNtfyUseCase(
    private val ntfyStore: NtfyStore,
    private val pusherSubscriber: PusherSubscriber,
    private val ntfyWebSocketManager: NtfyWebSocketManager,
) : UnregisterNtfyUseCase {
    override suspend fun unregister(matrixClient: MatrixClient, clientSecret: String): Result<Unit> {
        val config = ntfyStore.getConfig(clientSecret)
        if (config == null) {
            Timber.tag(loggerTag.value).w("No configuration found for the session, cleaning up anyway")
            cleanup(clientSecret)
            return Result.success(Unit)
        }
        return pusherSubscriber.unregisterPusher(
            matrixClient = matrixClient,
            pushKey = config.publishUrl,
            gateway = config.pushGatewayUrl,
        )
            .onSuccess { cleanup(clientSecret) }
            .onFailure {
                Timber.tag(loggerTag.value).e(it, "Unable to unregister the pusher")
            }
    }

    override fun cleanup(clientSecret: String) {
        ntfyWebSocketManager.stop(clientSecret)
        ntfyStore.clear(clientSecret)
    }
}
