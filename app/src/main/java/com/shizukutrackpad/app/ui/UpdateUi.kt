package com.shizukutrackpad.app.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shizukutrackpad.app.R
import com.shizukutrackpad.app.update.ReleaseInfo
import com.shizukutrackpad.app.update.UpdateError
import com.shizukutrackpad.app.update.UpdateState
import com.shizukutrackpad.app.ui.theme.TrackpadRadius
import com.shizukutrackpad.app.ui.theme.TrackpadTokens
import com.shizukutrackpad.app.ui.theme.TrackpadType

/**
 * Slim strip under the top chrome: offers a new release, shows download progress, and
 * surfaces download/install failures. Hidden for a version the user already dismissed.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    dismissedVersion: String?,
    onUpdate: () -> Unit,
    onDismiss: (ReleaseInfo) -> Unit
) {
    val visible = when (state) {
        is UpdateState.Available -> state.release.versionName != dismissedVersion
        is UpdateState.Downloading, is UpdateState.Installing -> true
        is UpdateState.Failed -> state.release != null && state.release.versionName != dismissedVersion
        else -> false
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TrackpadTokens.DeckSurface)
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            HorizontalDivider(color = TrackpadTokens.Hairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = bannerMessage(state),
                    fontSize = TrackpadType.Label,
                    color = if (state is UpdateState.Failed) TrackpadTokens.LedError else TrackpadTokens.InkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                when (state) {
                    is UpdateState.Available -> {
                        PillButton(label = stringResource(R.string.update_action_install), onClick = onUpdate)
                        CloseButton(
                            description = stringResource(R.string.update_action_dismiss),
                            onClick = { onDismiss(state.release) }
                        )
                    }
                    is UpdateState.Failed -> state.release?.let { release ->
                        PillButton(label = stringResource(R.string.update_action_retry), onClick = onUpdate)
                        CloseButton(
                            description = stringResource(R.string.update_action_dismiss),
                            onClick = { onDismiss(release) }
                        )
                    }
                    else -> Unit
                }
            }
            if (state is UpdateState.Downloading) {
                UpdateProgressBar(progress = state.progress)
            }
            HorizontalDivider(color = TrackpadTokens.Hairline)
        }
    }
}

/** "App update" block at the bottom of the settings sheet. */
@Composable
fun UpdateSettingsSection(
    state: UpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onUpdate: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.settings_update_title),
                fontSize = TrackpadType.Body,
                fontWeight = FontWeight.Medium,
                color = TrackpadTokens.InkPrimary
            )
            Text(
                text = stringResource(R.string.settings_update_current, currentVersion),
                fontSize = TrackpadType.Caption,
                fontFamily = FontFamily.Monospace,
                color = TrackpadTokens.InkSecondary
            )
        }

        val status: String? = when (state) {
            UpdateState.Unsupported -> stringResource(R.string.update_unsupported)
            UpdateState.Idle -> null
            UpdateState.Checking -> stringResource(R.string.update_checking)
            is UpdateState.UpToDate -> if (state.latestVersion == null) {
                stringResource(R.string.update_no_releases)
            } else {
                stringResource(R.string.update_up_to_date)
            }
            is UpdateState.Available -> stringResource(R.string.update_available_detail, state.release.versionName)
            is UpdateState.Downloading, is UpdateState.Installing -> bannerMessage(state)
            is UpdateState.Failed -> stringResource(errorMessageRes(state.error))
        }
        if (status != null) {
            Text(
                text = status,
                fontSize = TrackpadType.Caption,
                color = if (state is UpdateState.Failed) TrackpadTokens.LedError else TrackpadTokens.InkSecondary
            )
        }
        if (state is UpdateState.Downloading) {
            UpdateProgressBar(progress = state.progress)
        }

        val action: Pair<Int, () -> Unit>? = when (state) {
            UpdateState.Idle, is UpdateState.UpToDate -> R.string.update_check to onCheck
            is UpdateState.Available -> R.string.update_action_download_install to onUpdate
            is UpdateState.Failed -> if (state.release != null) {
                R.string.update_action_retry to onUpdate
            } else {
                R.string.update_check to onCheck
            }
            else -> null
        }
        if (action != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(TrackpadRadius.M))
                    .clickable(role = Role.Button, onClick = action.second),
                shape = RoundedCornerShape(TrackpadRadius.M),
                color = if (state is UpdateState.Available) TrackpadTokens.InkPrimary else TrackpadTokens.KeycapSurface,
                border = BorderStroke(1.dp, TrackpadTokens.KeycapBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(action.first),
                        fontSize = TrackpadType.Body,
                        fontWeight = FontWeight.Medium,
                        color = if (state is UpdateState.Available) TrackpadTokens.InkInverse else TrackpadTokens.InkPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun bannerMessage(state: UpdateState): String = when (state) {
    is UpdateState.Available -> stringResource(R.string.update_banner_available, state.release.versionName)
    is UpdateState.Downloading -> state.progress?.let {
        stringResource(R.string.update_downloading, (it * 100).toInt())
    } ?: stringResource(R.string.update_downloading_indeterminate)
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.Failed -> stringResource(errorMessageRes(state.error))
    else -> ""
}

@StringRes
private fun errorMessageRes(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.NO_APK -> R.string.update_error_no_apk
    UpdateError.DOWNLOAD -> R.string.update_error_download
    UpdateError.INVALID_APK -> R.string.update_error_invalid
    UpdateError.SIGNATURE_MISMATCH -> R.string.update_error_signature
    UpdateError.INSTALL -> R.string.update_error_install
}

@Composable
private fun UpdateProgressBar(progress: Float?) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(2.dp)
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier,
            color = TrackpadTokens.AccentSignal,
            trackColor = TrackpadTokens.TrackInactive
        )
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier,
            color = TrackpadTokens.AccentSignal,
            trackColor = TrackpadTokens.TrackInactive,
            drawStopIndicator = {}
        )
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    // 44dp touch target around a 30dp visual pill
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(TrackpadRadius.M))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(TrackpadRadius.M))
                .background(TrackpadTokens.InkPrimary)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = TrackpadType.Label,
                fontWeight = FontWeight.SemiBold,
                color = TrackpadTokens.InkInverse
            )
        }
    }
}

@Composable
private fun CloseButton(description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(TrackpadRadius.M))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { this.contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            val stroke = 1.6.dp.toPx()
            drawLine(
                color = TrackpadTokens.InkSecondary,
                start = Offset.Zero,
                end = Offset(size.width, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = TrackpadTokens.InkSecondary,
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}
