/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.pushproviders.api.Config
import io.element.android.libraries.pushproviders.api.Distributor
import io.element.android.libraries.pushproviders.api.PushProvider
import io.element.android.libraries.pushproviders.ntfy.registration.RegisterNtfyUseCase
import io.element.android.libraries.pushproviders.ntfy.registration.UnregisterNtfyUseCase
import io.element.android.libraries.pushproviders.ntfy.store.NtfyStore
import io.element.android.libraries.pushstore.api.clientsecret.PushClientSecret

/**
 * Push provider receiving the notifications through a ntfy topic, without going through Google.
 *
 * The session is registered with the ntfy server itself acting as the Matrix push gateway, and the
 * app subscribes to the topic over a WebSocket, see [NtfyWebSocketManager].
 */
@ContributesIntoSet(AppScope::class)
class NtfyPushProvider(
    private val pushClientSecret: PushClientSecret,
    private val registerNtfyUseCase: RegisterNtfyUseCase,
    private val unregisterNtfyUseCase: UnregisterNtfyUseCase,
    private val ntfyStore: NtfyStore,
) : PushProvider {
    override val index = NtfyConfig.INDEX
    override val name = NtfyConfig.NAME

    /** A session is subscribed to a single topic, so there is nothing to choose. */
    override val supportMultipleDistributors = false

    override fun getDistributors(): List<Distributor> = listOf(ntfyDistributor)

    override suspend fun registerWith(matrixClient: MatrixClient, distributor: Distributor): Result<Unit> {
        val clientSecret = pushClientSecret.getSecretForUser(matrixClient.sessionId)
        return registerNtfyUseCase.execute(matrixClient, clientSecret)
    }

    override suspend fun getCurrentDistributorValue(sessionId: SessionId): String = ntfyDistributor.value

    override suspend fun getCurrentDistributor(sessionId: SessionId): Distributor = ntfyDistributor

    override suspend fun unregister(matrixClient: MatrixClient): Result<Unit> {
        val clientSecret = pushClientSecret.getSecretForUser(matrixClient.sessionId)
        return unregisterNtfyUseCase.unregister(matrixClient, clientSecret)
    }

    override suspend fun onSessionDeleted(sessionId: SessionId) {
        val clientSecret = pushClientSecret.getSecretForUser(sessionId)
        unregisterNtfyUseCase.cleanup(clientSecret)
    }

    override suspend fun getPushConfig(sessionId: SessionId): Config? {
        val clientSecret = pushClientSecret.getSecretForUser(sessionId)
        val config = ntfyStore.getConfig(clientSecret) ?: return null
        return Config(
            url = config.pushGatewayUrl,
            pushKey = config.publishUrl,
        )
    }

    override fun canRotateToken(): Boolean = false

    companion object {
        private val ntfyDistributor = Distributor(NtfyConfig.NAME, NtfyConfig.NAME)
    }
}
