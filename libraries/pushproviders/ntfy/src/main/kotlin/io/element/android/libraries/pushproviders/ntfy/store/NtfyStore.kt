/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.pushproviders.ntfy.store

import android.content.SharedPreferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.androidutils.json.JsonProvider
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.pushproviders.ntfy.model.NtfyConfigData

/**
 * Persists the ntfy configuration of a session, keyed by the client secret.
 *
 * The client secret is the only session identifier available when a notification arrives: it is
 * carried by the gateway payload, see `defaultPayload` in `DefaultPusherSubscriber`.
 */
interface NtfyStore {
    /** Configuration of the session, or `null` when it is not registered. */
    fun getConfig(clientSecret: String): NtfyConfigData?

    fun storeConfig(clientSecret: String, config: NtfyConfigData)

    /** Id of the last message handed to the push pipeline, used to catch up on missed messages. */
    fun getLastMessageId(clientSecret: String): String?

    fun storeLastMessageId(clientSecret: String, messageId: String)

    /** Removes everything stored for the session. */
    fun clear(clientSecret: String)
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class SharedPreferencesNtfyStore(
    private val sharedPreferences: SharedPreferences,
    private val jsonProvider: JsonProvider,
) : NtfyStore {
    override fun getConfig(clientSecret: String): NtfyConfigData? {
        val value = sharedPreferences.getString(KEY_CONFIG + clientSecret, null) ?: return null
        return tryOrNull { jsonProvider().decodeFromString(NtfyConfigData.serializer(), value) }
    }

    override fun storeConfig(clientSecret: String, config: NtfyConfigData) {
        val value = jsonProvider().encodeToString(NtfyConfigData.serializer(), config)
        sharedPreferences.edit()
            .putString(KEY_CONFIG + clientSecret, value)
            .apply()
    }

    override fun getLastMessageId(clientSecret: String): String? =
        sharedPreferences.getString(KEY_LAST_MESSAGE_ID + clientSecret, null)

    override fun storeLastMessageId(clientSecret: String, messageId: String) {
        sharedPreferences.edit()
            .putString(KEY_LAST_MESSAGE_ID + clientSecret, messageId)
            .apply()
    }

    override fun clear(clientSecret: String) {
        sharedPreferences.edit()
            .remove(KEY_CONFIG + clientSecret)
            .remove(KEY_LAST_MESSAGE_ID + clientSecret)
            .apply()
    }

    companion object {
        private const val KEY_CONFIG = "NTFY_CONFIG"
        private const val KEY_LAST_MESSAGE_ID = "NTFY_LAST_MESSAGE_ID"
    }
}
