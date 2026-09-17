/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.troubleshoot

import dev.zacsweers.metro.ContributesIntoSet
import io.element.android.libraries.di.SessionScope
import io.element.android.libraries.pushproviders.ntfy.NtfyConfig
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootNavigator
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTest
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTestDelegate
import io.element.android.libraries.troubleshoot.api.test.NotificationTroubleshootTestState
import io.element.android.libraries.troubleshoot.api.test.TestFilterData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Checks the battery optimization state up front, instead of waiting for a push to fail: with ntfy
 * the connection is kept by the app itself, so an optimized app loses it until the user opens the app
 * again. Only relevant when ntfy is the provider, since the other ones are woken up by the system.
 */
@ContributesIntoSet(SessionScope::class)
class NtfyBatteryOptimizationTest(
    private val ntfyBatteryOptimization: NtfyBatteryOptimization,
) : NotificationTroubleshootTest {
    override val order = 455
    private val delegate = NotificationTroubleshootTestDelegate(
        defaultName = "Battery optimization",
        defaultDescription = "ntfy keeps a permanent connection, so the app must be exempted from battery optimization.",
        visibleWhenIdle = false,
        fakeDelay = NotificationTroubleshootTestDelegate.SHORT_DELAY,
    )
    override val state: StateFlow<NotificationTroubleshootTestState> = delegate.state

    override fun isRelevant(data: TestFilterData): Boolean {
        return data.currentPushProviderName == NtfyConfig.NAME
    }

    override suspend fun run(coroutineScope: CoroutineScope) {
        delegate.start()
        if (ntfyBatteryOptimization.isIgnoringBatteryOptimizations()) {
            delegate.updateState(
                description = "Battery optimization is disabled for the app.",
                status = NotificationTroubleshootTestState.Status.Success,
            )
        } else {
            delegate.updateState(
                description = "The app is optimized by the system, which will drop the connection.",
                status = NotificationTroubleshootTestState.Status.Failure(hasQuickFix = true),
            )
        }
    }

    override suspend fun quickFix(
        coroutineScope: CoroutineScope,
        navigator: NotificationTroubleshootNavigator,
    ) {
        delegate.start()
        ntfyBatteryOptimization.requestDisablingBatteryOptimization()
        run(coroutineScope)
    }

    override suspend fun reset() = delegate.reset()
}
