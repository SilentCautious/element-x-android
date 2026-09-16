/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.troubleshoot

import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.pushproviders.ntfy.NtfyConfig
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTest
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTestDelegate
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTestState
import io.element.android.libraries.troubleshoot.api.test.TestFilterData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@ContributesIntoSet(SessionScope::class)
class NtfySubscriptionTest(
    private val sessionId: SessionId,
    private val ntfyTroubleshooter: NtfyTroubleshooter,
) : NotificationTroubleshootTest {
    override val order = 460
    private val delegate = NotificationTroubleshootTestDelegate(
        defaultName = "Test the ntfy subscription",
        defaultDescription = "Check that the ntfy server answers for the topic, and subscribe again to recover from a connection that stopped.",
        visibleWhenIdle = false,
        fakeDelay = NotificationTroubleshootTestDelegate.SHORT_DELAY,
    )
    override val state: StateFlow<NotificationTroubleshootTestState> = delegate.state

    override fun isRelevant(data: TestFilterData): Boolean {
        return data.currentPushProviderName == NtfyConfig.NAME
    }

    override suspend fun run(coroutineScope: CoroutineScope) {
        delegate.start()
        coroutineScope.launch {
            ntfyTroubleshooter.troubleshoot(sessionId).fold(
                onSuccess = { topicUrl ->
                    delegate.updateState(
                        description = "Subscribed to $topicUrl.",
                        status = NotificationTroubleshootTestState.Status.Success,
                    )
                },
                onFailure = { throwable ->
                    delegate.updateState(
                        description = "Unable to subscribe: ${throwable.localizedMessage}",
                        status = NotificationTroubleshootTestState.Status.Failure(),
                    )
                },
            )
        }
    }

    override suspend fun reset() = delegate.reset()
}
