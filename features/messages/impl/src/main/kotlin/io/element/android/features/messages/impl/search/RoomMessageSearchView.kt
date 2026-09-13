/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.search

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.atomic.molecules.IconTitleSubtitleMolecule
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.components.avatar.Avatar
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.designsystem.components.avatar.AvatarType
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.modifiers.niceClickable
import io.element.android.libraries.designsystem.theme.components.FilledTextField
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.IconButton
import io.element.android.libraries.designsystem.theme.components.LinearProgressIndicator
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.designsystem.utils.OnVisibleRangeChangeEffect
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.room.RoomMember
import io.element.android.libraries.matrix.ui.components.AttachmentThumbnail
import io.element.android.libraries.matrix.ui.components.AttachmentThumbnailInfo
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomMessageSearchView(
    state: RoomMessageSearchState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMemberPicker by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            RoomMessageSearchTopBar(
                queryState = state.queryState,
                eventSink = state.eventSink,
                onBackClick = onBackClick,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.hasActiveQuery) {
                FilterChipsRow(
                    filter = state.filter,
                    roomMembers = state.roomMembers,
                    onAddMemberClick = { showMemberPicker = true },
                    onDateClick = { showDatePicker = true },
                    eventSink = state.eventSink,
                )
            }
            SearchResults(
                state = state,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showMemberPicker) {
        MemberPickerBottomSheet(
            state = state,
            onDismiss = { showMemberPicker = false },
        )
    }
    if (showDatePicker) {
        DateRangeDialog(
            state = state,
            onDismiss = { showDatePicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomMessageSearchTopBar(
    queryState: androidx.compose.foundation.text.input.TextFieldState,
    eventSink: (RoomMessageSearchEvent) -> Unit,
    onBackClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val searchLabel = stringResource(R.string.screen_room_message_search_title)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    TopAppBar(
        navigationIcon = { BackButton(onClick = onBackClick) },
        title = {
            FilledTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = searchLabel },
                state = queryState,
                placeholder = { Text(stringResource(R.string.screen_room_message_search_search_hint)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                trailingIcon = if (queryState.text.isNotEmpty()) {
                    @Composable {
                        IconButton(onClick = { eventSink(RoomMessageSearchEvent.UpdateQuery("")) }) {
                            Icon(
                                imageVector = CompoundIcons.Close(),
                                contentDescription = stringResource(CommonStrings.a11y_clear_search_field),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        },
    )
}

@Composable
private fun FilterChipsRow(
    filter: RoomMessageSearchFilter,
    roomMembers: ImmutableList<RoomMember>,
    onAddMemberClick: () -> Unit,
    onDateClick: () -> Unit,
    eventSink: (RoomMessageSearchEvent) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filter.senderIds.forEach { senderId ->
            val member = roomMembers.firstOrNull { it.userId == senderId }
            val label = member?.disambiguatedDisplayName ?: senderId.value
            FilterChip(
                label = label,
                icon = CompoundIcons.User(),
                onClick = { eventSink(RoomMessageSearchEvent.ToggleSender(senderId)) },
            )
        }
        FilterChip(
            label = stringResource(R.string.screen_room_message_search_filter_members),
            icon = CompoundIcons.User(),
            onClick = onAddMemberClick,
        )
        FilterChip(
            label = dateRangeLabel(filter.dateRange),
            icon = CompoundIcons.Calendar(),
            onClick = onDateClick,
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(ElementTheme.colors.bgSubtleSecondary)
            .niceClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = ElementTheme.colors.iconPrimary,
        )
        Text(
            text = label,
            style = ElementTheme.typography.fontBodySmMedium,
            color = ElementTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun dateRangeLabel(dateRange: LongRange?): String {
    if (dateRange == null) return stringResource(R.string.screen_room_message_search_date_any)
    val start = Instant.ofEpochMilli(dateRange.first).atZone(ZoneId.systemDefault()).toLocalDate()
    val end = Instant.ofEpochMilli(dateRange.last).atZone(ZoneId.systemDefault()).toLocalDate()
    return if (start == end) {
        start.toString()
    } else {
        "$start - $end"
    }
}

@Composable
private fun SearchResults(
    state: RoomMessageSearchState,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    when (val results = state.results) {
        is AsyncData.Uninitialized -> StartSearchView(modifier)
        is AsyncData.Loading -> {
            if (results.prevData == null) {
                Box(modifier = modifier, contentAlignment = Alignment.Center) {
                    LinearProgressIndicator()
                }
            } else {
                ResultList(
                    results = results.prevData!!,
                    lazyListState = lazyListState,
                    autoPaginationExhausted = state.autoPaginationExhausted,
                    eventSink = state.eventSink,
                    modifier = modifier,
                )
            }
        }
        is AsyncData.Failure -> ErrorView(modifier)
        is AsyncData.Success -> {
            if (results.data.isEmpty()) {
                NoResultsView(modifier)
            } else {
                ResultList(
                    results = results.data,
                    lazyListState = lazyListState,
                    autoPaginationExhausted = state.autoPaginationExhausted,
                    eventSink = state.eventSink,
                    modifier = modifier,
                )
            }
        }
    }
}

@Composable
private fun StartSearchView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        IconTitleSubtitleMolecule(
            title = stringResource(R.string.screen_room_message_search_start_title),
            subTitle = stringResource(R.string.screen_room_message_search_start_subtitle),
            iconStyle = BigIcon.Style.Default(vectorIcon = CompoundIcons.Search()),
        )
    }
}

@Composable
private fun NoResultsView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        IconTitleSubtitleMolecule(
            title = stringResource(R.string.screen_room_message_search_no_results_title),
            subTitle = stringResource(R.string.screen_room_message_search_no_results_subtitle),
            iconStyle = BigIcon.Style.Default(vectorIcon = CompoundIcons.Search()),
        )
    }
}

@Composable
private fun ErrorView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        IconTitleSubtitleMolecule(
            title = stringResource(CommonStrings.common_error),
            subTitle = null,
            iconStyle = BigIcon.Style.Default(
                vectorIcon = CompoundIcons.Error(),
                useCriticalTint = true,
            ),
        )
    }
}

@Composable
private fun ResultList(
    results: ImmutableList<RoomMessageSearchResultItem>,
    lazyListState: LazyListState,
    autoPaginationExhausted: Boolean,
    eventSink: (RoomMessageSearchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = lazyListState,
        modifier = modifier.fillMaxSize(),
    ) {
        resultItems(
            results = results,
            onResultClick = { eventId -> eventSink(RoomMessageSearchEvent.ResultSelected(eventId)) },
        )
        if (autoPaginationExhausted) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .niceClickable { eventSink(RoomMessageSearchEvent.LoadMore) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.screen_room_message_search_load_more),
                        style = ElementTheme.typography.fontBodyMdMedium,
                        color = ElementTheme.colors.textActionPrimary,
                    )
                }
            }
        }
    }
    OnVisibleRangeChangeEffect(lazyListState) { range ->
        eventSink(RoomMessageSearchEvent.UpdateVisibleRange(range))
    }
}

private fun LazyListScope.resultItems(
    results: ImmutableList<RoomMessageSearchResultItem>,
    onResultClick: (EventId) -> Unit,
) {
    itemsIndexed(
        items = results,
        contentType = { _, item -> item::class.java },
        key = { _, item -> item.eventId },
    ) { index, result ->
        val modifier = Modifier
            .fillMaxWidth()
            .niceClickable { onResultClick(result.eventId) }
        when (result) {
            is RoomMessageSearchResultItem.Message -> ResultMessageItem(result, modifier)
            is RoomMessageSearchResultItem.Media -> ResultMediaItem(result, modifier)
        }
    }
}

@Composable
private fun ResultMessageItem(result: RoomMessageSearchResultItem.Message, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarData = AvatarData(id = result.senderId.value, name = result.senderName, size = AvatarSize.RoomListItem),
            avatarType = AvatarType.Room(heroes = persistentListOf(), isTombstoned = false),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = result.senderName,
                    maxLines = 1,
                    style = ElementTheme.typography.fontBodyLgMedium,
                )
                Text(
                    text = result.formattedTimestamp,
                    maxLines = 1,
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
            Text(
                text = result.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = ElementTheme.typography.fontBodyMdRegular,
                color = ElementTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ResultMediaItem(result: RoomMessageSearchResultItem.Media, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarData = AvatarData(id = result.senderId.value, name = result.senderName, size = AvatarSize.RoomListItem),
            avatarType = AvatarType.Room(heroes = persistentListOf(), isTombstoned = false),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = result.senderName,
                    maxLines = 1,
                    style = ElementTheme.typography.fontBodyLgMedium,
                )
                Text(
                    text = result.formattedTimestamp,
                    maxLines = 1,
                    style = ElementTheme.typography.fontBodySmRegular,
                    color = ElementTheme.colors.textSecondary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ElementTheme.colors.bgSubtleSecondary, shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AttachmentThumbnail(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    info = AttachmentThumbnailInfo(
                        type = result.thumbnailType,
                        thumbnailSource = result.thumbnailSource,
                        textContent = null,
                        blurHash = null,
                    ),
                )
                Column {
                    Text(
                        text = result.filename,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = ElementTheme.typography.fontBodyLgRegular,
                    )
                    result.caption?.let {
                        Text(
                            text = it,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = ElementTheme.typography.fontBodySmRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                    }
                    result.formattedSize?.let {
                        Text(
                            text = "($it)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = ElementTheme.typography.fontBodySmRegular,
                            color = ElementTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberPickerBottomSheet(
    state: RoomMessageSearchState,
    onDismiss: () -> Unit,
) {
    val searchState = rememberTextFieldState()
    val query = searchState.text.toString()
    val filteredMembers = remember(query, state.roomMembers) {
        state.roomMembers.filter { member ->
            query.isBlank() ||
                member.displayNameOrDefault.contains(query, ignoreCase = true) ||
                member.userId.value.contains(query, ignoreCase = true)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        scrollable = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.screen_room_message_search_filter_members),
                style = ElementTheme.typography.fontHeadingMdBold,
            )
            Spacer(Modifier.height(12.dp))
            FilledTextField(
                state = searchState,
                placeholder = { Text(stringResource(CommonStrings.common_search_for_someone)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (filteredMembers.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(CommonStrings.common_no_results),
                        style = ElementTheme.typography.fontBodyMdRegular,
                        color = ElementTheme.colors.textSecondary,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.height(400.dp)) {
                    itemsIndexed(
                        items = filteredMembers,
                        contentType = { _, _ -> RoomMember::class.java },
                        key = { _, member -> member.userId },
                    ) { _, member ->
                        val isSelected = member.userId in state.filter.senderIds
                        MemberPickerRow(
                            member = member,
                            isSelected = isSelected,
                            onClick = { state.eventSink(RoomMessageSearchEvent.ToggleSender(member.userId)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberPickerRow(
    member: RoomMember,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .niceClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            avatarData = AvatarData(id = member.userId.value, name = member.disambiguatedDisplayName, size = AvatarSize.RoomListItem),
            avatarType = AvatarType.Room(heroes = persistentListOf(), isTombstoned = false),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            modifier = Modifier.weight(1f),
            text = member.disambiguatedDisplayName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = ElementTheme.typography.fontBodyLgMedium,
        )
        if (isSelected) {
            Icon(
                imageVector = CompoundIcons.Check(),
                contentDescription = null,
                tint = ElementTheme.colors.iconPrimary,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(
    state: RoomMessageSearchState,
    onDismiss: () -> Unit,
) {
    var showCustomPicker by rememberSaveable { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()
    BasicAlertDialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(28.dp),
            color = ElementTheme.colors.bgCanvasDefault,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            ) {
                Text(
                    text = stringResource(R.string.screen_room_message_search_filter_date),
                    style = ElementTheme.typography.fontHeadingMdBold,
                )
                Spacer(Modifier.height(16.dp))
                if (!showCustomPicker) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DatePresetChip(
                            label = stringResource(R.string.screen_room_message_search_date_any),
                            selected = state.filter.dateRange == null,
                            onClick = {
                                state.eventSink(RoomMessageSearchEvent.SetDateRange(null))
                                onDismiss()
                            },
                        )
                        DatePresetChip(
                            label = stringResource(R.string.screen_room_message_search_date_today),
                            selected = state.filter.dateRange == presetDateRange(DatePreset.TODAY),
                            onClick = {
                                state.eventSink(RoomMessageSearchEvent.SetDateRange(presetDateRange(DatePreset.TODAY)))
                                onDismiss()
                            },
                        )
                        DatePresetChip(
                            label = stringResource(R.string.screen_room_message_search_date_last_7_days),
                            selected = state.filter.dateRange == presetDateRange(DatePreset.LAST_7_DAYS),
                            onClick = {
                                state.eventSink(RoomMessageSearchEvent.SetDateRange(presetDateRange(DatePreset.LAST_7_DAYS)))
                                onDismiss()
                            },
                        )
                        DatePresetChip(
                            label = stringResource(R.string.screen_room_message_search_date_last_30_days),
                            selected = state.filter.dateRange == presetDateRange(DatePreset.LAST_30_DAYS),
                            onClick = {
                                state.eventSink(RoomMessageSearchEvent.SetDateRange(presetDateRange(DatePreset.LAST_30_DAYS)))
                                onDismiss()
                            },
                        )
                        DatePresetChip(
                            label = stringResource(R.string.screen_room_message_search_date_custom),
                            selected = showCustomPicker,
                            onClick = { showCustomPicker = true },
                        )
                    }
                } else {
                    DateRangePicker(
                        state = dateRangePickerState,
                        modifier = Modifier.fillMaxWidth(),
                        showModeToggle = false,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showCustomPicker = false }) {
                            Text(stringResource(CommonStrings.action_cancel))
                        }
                        TextButton(
                            enabled = dateRangePickerState.selectedStartDateMillis != null && dateRangePickerState.selectedEndDateMillis != null,
                            onClick = {
                                val start = dateRangePickerState.selectedStartDateMillis
                                val end = dateRangePickerState.selectedEndDateMillis
                                if (start != null && end != null) {
                                    state.eventSink(RoomMessageSearchEvent.SetDateRange(customRangeFromMillis(start, end)))
                                    onDismiss()
                                }
                            },
                        ) {
                            Text(stringResource(CommonStrings.action_ok))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DatePresetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (selected) ElementTheme.colors.bgActionPrimaryRest else ElementTheme.colors.bgSubtleSecondary
    val contentColor = if (selected) ElementTheme.colors.textActionPrimary else ElementTheme.colors.textPrimary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(backgroundColor)
            .niceClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = ElementTheme.typography.fontBodySmMedium,
            color = contentColor,
        )
    }
}

private enum class DatePreset {
    ANY,
    TODAY,
    LAST_7_DAYS,
    LAST_30_DAYS,
    CUSTOM
}

private fun presetDateRange(preset: DatePreset, today: LocalDate = LocalDate.now()): LongRange? = when (preset) {
    DatePreset.ANY -> null
    DatePreset.TODAY -> today.asDayRange()
    DatePreset.LAST_7_DAYS -> today.minusDays(6).asDayRange()
    DatePreset.LAST_30_DAYS -> today.minusDays(29).asDayRange()
    DatePreset.CUSTOM -> null
}

private fun LocalDate.asDayRange(): LongRange {
    val start = atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endExclusive = plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return start until endExclusive
}

private fun customRangeFromMillis(startUtcMillis: Long, endUtcMillis: Long): LongRange {
    val startDate = Instant.ofEpochMilli(startUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val endDate = Instant.ofEpochMilli(endUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val start = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val endExclusive = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return start until endExclusive
}
