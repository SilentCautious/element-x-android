/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.sticker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.atomic.molecules.IconTitleSubtitleMolecule
import io.element.android.libraries.designsystem.components.BigIcon
import io.element.android.libraries.designsystem.modifiers.niceClickable
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.ModalBottomSheet
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.media.MediaSource
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import io.element.android.libraries.ui.strings.CommonStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPickerBottomSheet(
    isVisible: Boolean,
    state: StickerPickerState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.sendResult) {
        if (state.sendResult == true) {
            state.eventSink(StickerPickerEvent.Dismiss)
            onDismiss()
        }
    }
    if (isVisible) {
        ModalBottomSheet(
            modifier = modifier,
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
            onDismissRequest = onDismiss,
            scrollable = false,
        ) {
            StickerPickerContent(state = state)
        }
    }
}

@Composable
private fun StickerPickerContent(state: StickerPickerState) {
    val shouldShowReload = state.error == StickerPickerError.Load ||
        (state.stickers is AsyncData.Success && state.stickers.dataOrNull().isNullOrEmpty())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.screen_sticker_picker_title),
                style = ElementTheme.typography.fontBodyLgMedium,
                modifier = Modifier.weight(1f),
            )
            if (shouldShowReload) {
                Text(
                    text = stringResource(CommonStrings.action_retry),
                    style = ElementTheme.typography.fontBodyMdMedium,
                    color = ElementTheme.colors.textActionPrimary,
                    modifier = Modifier
                        .niceClickable { state.eventSink(StickerPickerEvent.Reload) }
                        .padding(8.dp),
                )
            }
        }
        when {
            state.stickers is AsyncData.Loading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            state.stickers.dataOrNull().isNullOrEmpty() -> {
                IconTitleSubtitleMolecule(
                    title = stringResource(R.string.screen_sticker_picker_empty_title),
                    subTitle = stringResource(R.string.screen_sticker_picker_empty_subtitle),
                    iconStyle = BigIcon.Style.Default(CompoundIcons.Sticker()),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 32.dp),
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(state.stickers.dataOrNull()!!, key = { it.shortcode }) { sticker ->
                        StickerGridItem(sticker = sticker, state = state)
                    }
                }
            }
        }
        if (state.error != null) {
            Text(
                text = stringResource(CommonStrings.common_error),
                style = ElementTheme.typography.fontBodySmRegular,
                color = ElementTheme.colors.textCriticalPrimary,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun StickerGridItem(sticker: StickerImage, state: StickerPickerState) {
    val mediaRequest = remember(sticker.url) {
        MediaRequestData(
            source = MediaSource(url = sticker.url),
            kind = MediaRequestData.Kind.Content,
        )
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .niceClickable { state.eventSink(StickerPickerEvent.SelectSticker(sticker)) },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = mediaRequest,
            contentDescription = stringResource(R.string.a11y_sticker_picker_sticker, sticker.shortcode),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@PreviewsDayNight
@Composable
internal fun StickerPickerBottomSheetPreview(
    @PreviewParameter(StickerPickerStatePreviewParam::class) state: StickerPickerState,
) = ElementPreview {
    StickerPickerBottomSheet(
        isVisible = true,
        state = state,
        onDismiss = {},
    )
}
