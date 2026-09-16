/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy

object NtfyConfig {
    /**
     * Providers are sorted using this index, from lower to higher.
     * Firebase is 0, UnifiedPush is 1.
     */
    const val INDEX = 2

    const val NAME = "Ntfy"

    const val DEFAULT_SERVER_URL = "https://ntfy.sh"

    /**
     * Matrix push gateway of the default server. ntfy implements the Matrix push gateway itself, so
     * the gateway always belongs to the ntfy server hosting the topic, see `NtfyConfigData.pushGatewayUrl`.
     * Note: the URL should have the path '/_matrix/push/v1/notify'.
     */
    const val DEFAULT_PUSH_GATEWAY_URL = "$DEFAULT_SERVER_URL/_matrix/push/v1/notify"

    /** Priority used when publishing, 3 is the ntfy default ("default priority"). */
    const val DEFAULT_PRIORITY = 3

    /** Delay between two ping frames sent to keep the connection alive on a quiet topic. */
    const val HEARTBEAT_INTERVAL_SECONDS = 30L

    /** Delay before the first reconnection attempt, doubled on every subsequent failure. */
    const val BASE_RECONNECT_DELAY_SECONDS = 5L

    /** Upper bound of the reconnection backoff. */
    const val MAX_RECONNECT_DELAY_SECONDS = 300L

    /** Number of consecutive failures after which the client stops trying to reconnect. */
    const val MAX_RECONNECT_ATTEMPTS = 10
}
