/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.libraries.architecture.callback
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId

@ContributesNode(RoomScope::class)
@AssistedInject
class RoomMessageSearchNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: RoomMessageSearchPresenter.Factory,
) : Node(buildContext, plugins = plugins), RoomMessageSearchNavigator {
    interface Callback : Plugin {
        fun viewInTimeline(eventId: EventId)
    }

    private val callback: Callback = callback()
    private val presenter = presenterFactory.create(navigator = this)

    override fun onSearchResultSelected(eventId: EventId) {
        callback.viewInTimeline(eventId)
    }

    @Composable
    override fun View(modifier: Modifier) {
        RoomMessageSearchView(
            state = presenter.present(),
            onBackClick = ::navigateUp,
            modifier = modifier,
        )
    }
}
