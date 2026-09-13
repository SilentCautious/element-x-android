/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.search

import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.libraries.androidutils.filesize.FileSizeFormatter
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.map
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.dateformatter.api.DateFormatter
import io.element.android.libraries.dateformatter.api.DateFormatterMode
import io.element.android.libraries.eventformatter.api.RoomLatestEventFormatter
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.permalink.PermalinkParser
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomMembersState
import io.element.android.libraries.matrix.api.roomlist.LatestEventValue
import io.element.android.libraries.matrix.api.search.MessageSearch
import io.element.android.libraries.matrix.api.search.MessageSearchPaginationState
import io.element.android.libraries.matrix.api.search.MessageSearchResult
import io.element.android.libraries.matrix.api.search.MessageSearchService
import io.element.android.libraries.matrix.api.timeline.item.event.AudioMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.FileMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.ImageMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.MessageContent
import io.element.android.libraries.matrix.api.timeline.item.event.MessageTypeWithAttachment
import io.element.android.libraries.matrix.api.timeline.item.event.StickerMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VideoMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.VoiceMessageType
import io.element.android.libraries.matrix.api.timeline.item.event.isMediaContent
import io.element.android.libraries.matrix.ui.components.AttachmentThumbnailType
import io.element.android.libraries.matrix.ui.messages.toPlainText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

interface RoomMessageSearchNavigator {
    fun onSearchResultSelected(eventId: EventId)
}

@AssistedInject
class RoomMessageSearchPresenter(
    @Assisted private val navigator: RoomMessageSearchNavigator,
    private val room: JoinedRoom,
    private val messageSearchService: MessageSearchService,
    private val featureFlagService: FeatureFlagService,
    private val latestEventFormatter: RoomLatestEventFormatter,
    private val dateFormatter: DateFormatter,
    private val fileSizeFormatter: FileSizeFormatter,
    private val permalinkParser: PermalinkParser,
    private val coroutineDispatchers: CoroutineDispatchers,
) : Presenter<RoomMessageSearchState> {
    @AssistedFactory
    interface Factory {
        fun create(navigator: RoomMessageSearchNavigator): RoomMessageSearchPresenter
    }

    @Composable
    override fun present(): RoomMessageSearchState {
        val coroutineScope = rememberCoroutineScope()
        val queryState = rememberTextFieldState()
        var filter by remember { mutableStateOf(RoomMessageSearchFilter()) }
        val currentMessageSearch: MessageSearch = remember {
            messageSearchService.createMessageSearch(scope = coroutineScope, roomId = room.roomId)
        }
        var autoPaginationCount by remember { mutableIntStateOf(0) }
        var autoPaginationExhausted by remember { mutableStateOf(false) }
        var rawResults: AsyncData<ImmutableList<MessageSearchResult>> by remember {
            mutableStateOf(AsyncData.Uninitialized)
        }
        var endReached by remember { mutableStateOf(false) }

        val membersState by room.membersStateFlow.collectAsState()
        LaunchedEffect(Unit) { room.updateMembers() }
        val roomMembers by remember(membersState) {
            derivedStateOf {
                (membersState as? RoomMembersState.Ready)?.roomMembers
                    ?.filter { it.membership.isActive() }
                    .orEmpty()
                    .toImmutableList()
            }
        }

        val isEnabled by produceState(true) {
            featureFlagService.isFeatureEnabledFlow(FeatureFlags.MessageSearch).collectLatest { value = it }
        }

        LaunchedEffect(queryState.text) {
            delay(200.milliseconds)
            if (queryState.text.isNotEmpty()) {
                rawResults = AsyncData.Loading(prevData = rawResults.dataOrNull())
                autoPaginationCount = 0
                autoPaginationExhausted = false
                launch {
                    currentMessageSearch.setQuery(queryState.text.toString())
                        .onFailure { Timber.e(it, "Could not set query for message search") }
                }
            } else {
                rawResults = AsyncData.Uninitialized
            }
        }

        LaunchedEffect(filter) {
            autoPaginationCount = 0
            autoPaginationExhausted = false
        }

        LaunchedEffect(Unit) {
            combine(currentMessageSearch.results, currentMessageSearch.paginationState) { r, p -> r to p }
                .collectLatest { (searchResults, paginationState) ->
                    endReached = paginationState is MessageSearchPaginationState.Idle && paginationState.endReached
                    val filtered = searchResults.applyFilter(filter)
                    val idleNotEnded = paginationState is MessageSearchPaginationState.Idle && !paginationState.endReached
                    rawResults = when {
                        queryState.text.isEmpty() -> AsyncData.Uninitialized
                        filtered.isNotEmpty() -> AsyncData.Success(searchResults)
                        idleNotEnded && autoPaginationCount < MAX_AUTO_PAGINATIONS -> {
                            autoPaginationCount++
                            coroutineScope.launch { currentMessageSearch.paginate() }
                            AsyncData.Loading(prevData = rawResults.dataOrNull())
                        }
                        idleNotEnded -> {
                            autoPaginationExhausted = true
                            AsyncData.Loading(prevData = rawResults.dataOrNull())
                        }
                        endReached -> AsyncData.Success(persistentListOf())
                        else -> AsyncData.Loading(prevData = rawResults.dataOrNull())
                    }
                }
        }

        val results = remember {
            derivedStateOf {
                rawResults.map { raw -> mapResults(raw.applyFilter(filter)) }
            }
        }.value

        fun handleEvent(event: RoomMessageSearchEvent) {
            when (event) {
                is RoomMessageSearchEvent.ToggleSender -> {
                    val newSenders = filter.senderIds.toMutableSet()
                    if (event.senderId in newSenders) newSenders.remove(event.senderId) else newSenders.add(event.senderId)
                    filter = filter.copy(senderIds = newSenders)
                }
                is RoomMessageSearchEvent.SetDateRange -> filter = filter.copy(dateRange = event.dateRange)
                is RoomMessageSearchEvent.UpdateVisibleRange -> coroutineScope.launch {
                    val currentCount = rawResults.dataOrNull()?.size ?: 0
                    val canPaginate = currentMessageSearch.paginationState.value.let {
                        it is MessageSearchPaginationState.Idle && !it.endReached
                    }
                    if (event.range.last >= currentCount - 10 && canPaginate) {
                        currentMessageSearch.paginate()
                    }
                }
                RoomMessageSearchEvent.LoadMore -> coroutineScope.launch {
                    autoPaginationCount = 0
                    autoPaginationExhausted = false
                    currentMessageSearch.paginate()
                }
                is RoomMessageSearchEvent.ResultSelected -> navigator.onSearchResultSelected(event.eventId)
                is RoomMessageSearchEvent.UpdateQuery -> queryState.edit { replace(0, length, event.query) }
            }
        }

        return RoomMessageSearchState(
            queryState = queryState,
            filter = filter,
            hasActiveQuery = queryState.text.isNotEmpty() && isEnabled,
            roomMembers = roomMembers,
            results = results,
            endReached = endReached,
            autoPaginationExhausted = autoPaginationExhausted,
            eventSink = ::handleEvent,
        )
    }

    private fun mapResults(results: List<MessageSearchResult>): ImmutableList<RoomMessageSearchResultItem> {
        return results.mapNotNull { result ->
            val formattedTimestamp = dateFormatter.format(
                timestamp = result.timestamp,
                mode = DateFormatterMode.TimeOrDate,
                useRelative = true,
            )
            if (!result.content.isMediaContent()) {
                mapMessageContent(result, formattedTimestamp)
            } else {
                mapMediaContent(result, formattedTimestamp)
            }
        }.toImmutableList()
    }

    private fun mapMessageContent(result: MessageSearchResult, formattedTimestamp: String): RoomMessageSearchResultItem.Message? {
        val body = latestEventFormatter.format(
            latestEvent = LatestEventValue.Remote(
                timestamp = result.timestamp,
                content = result.content,
                senderId = result.senderId,
                senderProfile = result.senderProfile,
                isOwn = false,
            ),
            isDmRoom = false,
        )
        return RoomMessageSearchResultItem.Message(
            messageSearchResult = result,
            body = body?.toString() ?: "",
            formattedTimestamp = formattedTimestamp,
        )
    }

    private fun mapMediaContent(result: MessageSearchResult, formattedTimestamp: String): RoomMessageSearchResultItem.Media? {
        val content = result.content
        if (content !is MessageContent || content.type !is MessageTypeWithAttachment) return null
        val messageType = content.type as MessageTypeWithAttachment
        val caption = messageType.toPlainText(permalinkParser, default = messageType.caption ?: messageType.filename)
        val thumbnailType = when (messageType) {
            is ImageMessageType -> AttachmentThumbnailType.Image
            is VideoMessageType -> AttachmentThumbnailType.Video
            is AudioMessageType -> AttachmentThumbnailType.Audio
            is VoiceMessageType -> AttachmentThumbnailType.Voice
            is FileMessageType -> AttachmentThumbnailType.File
            is StickerMessageType -> AttachmentThumbnailType.Image
        }
        val thumbnailSource = when (messageType) {
            is ImageMessageType -> messageType.info?.thumbnailSource ?: messageType.source
            is VideoMessageType -> messageType.info?.thumbnailSource
            else -> null
        }
        val formattedSize = when (messageType) {
            is ImageMessageType -> messageType.info?.size
            is VideoMessageType -> messageType.info?.size
            is AudioMessageType -> messageType.info?.size
            is VoiceMessageType -> messageType.info?.size
            is FileMessageType -> messageType.info?.size
            is StickerMessageType -> messageType.info?.size
        }?.let(fileSizeFormatter::format)
        return RoomMessageSearchResultItem.Media(
            messageSearchResult = result,
            filename = messageType.filename,
            caption = caption,
            formattedSize = formattedSize,
            thumbnailSource = thumbnailSource,
            thumbnailType = thumbnailType,
            formattedTimestamp = formattedTimestamp,
        )
    }

    companion object {
        const val MAX_AUTO_PAGINATIONS = 10
    }
}
