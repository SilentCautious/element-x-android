/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.keepalive

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import io.element.android.libraries.core.log.logger.LoggerTag
import io.element.android.libraries.di.annotations.ApplicationContext
import timber.log.Timber

private val loggerTag = LoggerTag("NtfyKeepAliveServiceManager", LoggerTag.PushLoggerTag)

interface NtfyKeepAliveServiceManager {
    /**
     * Starts the foreground service keeping the connection alive.
     *
     * @return `false` when the service could not be started, typically because the app was in the
     * background, in which case the connection will only be kept while the app is visible.
     */
    fun start(): Boolean

    /** Stops the foreground service, releasing the persistent notification. */
    fun stop(): Boolean
}

@ContributesBinding(AppScope::class)
class DefaultNtfyKeepAliveServiceManager(
    @ApplicationContext private val context: Context,
) : NtfyKeepAliveServiceManager {
    override fun start(): Boolean {
        val intent = Intent(context, NtfyKeepAliveService::class.java)
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (throwable: Exception) {
            Timber.tag(loggerTag.value).e(throwable, "Unable to start the ntfy keep alive service")
            false
        }
    }

    override fun stop(): Boolean {
        val intent = Intent(context, NtfyKeepAliveService::class.java)
        return try {
            context.stopService(intent)
        } catch (throwable: Exception) {
            Timber.tag(loggerTag.value).e(throwable, "Unable to stop the ntfy keep alive service")
            false
        }
    }
}
