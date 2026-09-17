/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.keepalive

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.designsystem.utils.CommonDrawables
import io.element.android.libraries.pushproviders.ntfy.R
import timber.log.Timber

private const val NOTIFICATION_ID = 1002

private const val CHANNEL_ID = "ntfy_keep_alive_notification_channel"

/**
 * Foreground service keeping the app process out of the background execution limits, so that the
 * WebSocket subscription to the ntfy topic stays connected while the user is not looking at the app.
 *
 * The subscription itself is owned by `NtfyWebSocketManager`, a singleton of the process: as long as
 * this service keeps the process alive, the connection stays up. When the process is killed this
 * service goes with it, and the connection is only restored the next time the user opens the app,
 * since ntfy has no system channel able to wake the app up.
 *
 * No wakelock is taken here: holding one for as long as the app is installed would drain the battery,
 * and keeping the process in the foreground is enough to keep the socket open.
 */
class NtfyKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannelExists()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(CommonDrawables.ic_notification)
            .setContentTitle(getString(R.string.common_ntfy_keep_alive_notification_title))
            .setContentText(getString(R.string.common_ntfy_keep_alive_notification_content))
            .setOngoing(true)
            .setVibrate(longArrayOf(0))
            .setSound(null)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        runCatchingExceptions {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        }
            .onSuccess { Timber.d("NtfyKeepAliveService started in foreground") }
            .onFailure {
                Timber.e(it, "Failed to start NtfyKeepAliveService in foreground")
                stopSelf()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ask the system to restart the service if the process is killed, so that the subscription
        // gets a chance to be restored without waiting for the user to open the app again.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatchingExceptions {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }.onFailure { Timber.w(it, "Failed to stop the foreground state of NtfyKeepAliveService") }
        super.onDestroy()
    }

    private fun ensureNotificationChannelExists() {
        NotificationManagerCompat.from(this).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(getString(R.string.common_ntfy_keep_alive_channel_name))
                    .setVibrationEnabled(false)
                    .setSound(null, null)
                    .build()
            )
        )
    }
}
