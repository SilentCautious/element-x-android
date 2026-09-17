/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.troubleshoot

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.services.toolbox.api.intent.ExternalIntentLauncher
import timber.log.Timber

/**
 * Battery optimization state, checked from this module instead of from `libraries/push/impl`, so that
 * the provider does not have to depend on the implementation of the push feature.
 *
 * ntfy relies on a connection kept open by the app itself, so being optimized by the system is not
 * merely a delay issue: the connection is dropped and nothing wakes the app up again.
 */
interface NtfyBatteryOptimization {
    /** @return true when the app is allowed to run in the background without being optimized. */
    fun isIgnoringBatteryOptimizations(): Boolean

    /**
     * Opens the system settings so the user can exempt the app.
     *
     * @return true if the intent was launched.
     */
    fun requestDisablingBatteryOptimization(): Boolean
}

@ContributesBinding(AppScope::class)
class AndroidNtfyBatteryOptimization(
    @ApplicationContext private val context: Context,
    private val externalIntentLauncher: ExternalIntentLauncher,
) : NtfyBatteryOptimization {
    override fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    @SuppressLint("BatteryLife")
    override fun requestDisablingBatteryOptimization(): Boolean {
        if (launchAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, withData = true)) {
            return true
        }
        // Fall back on the full list of apps when the direct request is not available.
        return launchAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, withData = false)
    }

    private fun launchAction(
        action: String,
        withData: Boolean,
    ): Boolean {
        val intent = Intent().apply {
            this.action = action
            if (withData) {
                data = Uri.parse("package:${context.packageName}")
            }
        }
        return try {
            externalIntentLauncher.launch(intent)
            true
        } catch (exception: ActivityNotFoundException) {
            Timber.w(exception, "Cannot launch intent with action $action.")
            false
        }
    }
}
