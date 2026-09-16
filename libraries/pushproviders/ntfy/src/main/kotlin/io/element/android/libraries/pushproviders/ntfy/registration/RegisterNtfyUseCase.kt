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
import io.element.android.libraries.pushproviders.ntfy.model.NtfyConfigData
import io.element.android.libraries.pushproviders.ntfy.store.NtfyStore
import timber.log.Timber
import java.util.UUID

private val loggerTag = LoggerTag("RegisterNtfyUseCase", LoggerTag.PushLoggerTag)

interface RegisterNtfyUseCase {
    /**
     * Registers the pusher on the homeserver and subscribes to the topic.
     *
     * The topic of the session is reused when it already exists, so that calling this again — which
     * happens on every app start through `ensurePusherIsRegistered` — does not churn the registration.
     */
    suspend fun execute(matrixClient: MatrixClient, clientSecret: String): Result<Unit>
}

@ContributesBinding(AppScope::class)
class DefaultRegisterNtfyUseCase(
    private val ntfyStore: NtfyStore,
    private val pusherSubscriber: PusherSubscriber,
    private val ntfyWebSocketManager: NtfyWebSocketManager,
) : RegisterNtfyUseCase {
    override suspend fun execute(matrixClient: MatrixClient, clientSecret: String): Result<Unit> {
        val config = ntfyStore.getConfig(clientSecret) ?: NtfyConfigData(topic = generateTopic())
        ntfyStore.storeConfig(clientSecret, config)
        return pusherSubscriber.registerPusher(
            matrixClient = matrixClient,
            pushKey = config.publishUrl,
            gateway = config.pushGatewayUrl,
        )
            .onSuccess {
                // Subscribe right away: ntfy rejects a push key whose topic never had a subscriber.
                ntfyWebSocketManager.start(clientSecret, config)
            }
            .onFailure {
                Timber.tag(loggerTag.value).e(it, "Unable to register the pusher")
            }
    }

    /**
     * The topic is random: it is public on the server, and it must not leak the user identifier or the
     * client secret. The session is recovered from the client secret carried by the payload.
     */
    private fun generateTopic(): String = TOPIC_PREFIX + UUID.randomUUID().toString().replace("-", "")

    private companion object {
        private const val TOPIC_PREFIX = "elementx-"
    }
}
