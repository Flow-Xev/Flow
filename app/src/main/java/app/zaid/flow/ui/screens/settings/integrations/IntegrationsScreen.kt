package app.zaid.flow.ui.screens.settings.integrations

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.ThumbDownOffAlt
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.zaid.flow.BuildConfig
import app.zaid.flow.R
import app.zaid.flow.ui.components.settings.SettingsDestination
import app.zaid.flow.ui.components.settings.SettingsPage
import app.zaid.flow.ui.components.settings.SettingsTarget
import app.zaid.flow.ui.components.settings.nav
import app.zaid.flow.ui.components.settings.switch
import app.zaid.flow.ui.components.shared.FlowNavRow
import app.zaid.flow.ui.screens.settings.index.DestinationIndex
import app.zaid.flow.ui.screens.settings.index.IntegrationsIndex

/**
 * The outside services Flow can talk to: SponsorBlock, DeArrow, Return YouTube Dislike and, in
 * builds that include it, Discord Rich Presence. Each service's first row is its own switch, and
 * the rows that depend on it wait for it.
 */
@Composable
internal fun IntegrationsScreen(
    onBack: (() -> Unit)?,
    highlight: String?,
    onNavigate: (SettingsTarget) -> Unit,
    viewModel: IntegrationsViewModel = hiltViewModel(),
) {
    val sponsorBlock by viewModel.sponsorBlock.collectAsStateWithLifecycle()
    val submitButton by viewModel.submitButton.collectAsStateWithLifecycle()
    val userId by viewModel.userId.collectAsStateWithLifecycle()
    val segments by viewModel.segments.collectAsStateWithLifecycle()
    val deArrow by viewModel.deArrow.collectAsStateWithLifecycle()
    val discordState by viewModel.discordState.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val geminiKey by viewModel.geminiKey.collectAsStateWithLifecycle()
    val geminiModel by viewModel.geminiModel.collectAsStateWithLifecycle()
    val aiProvider by viewModel.aiProvider.collectAsStateWithLifecycle()
    val inspectorOn by viewModel.aiInspector.collectAsStateWithLifecycle()
    val inspectorKey by viewModel.inspectorKey.collectAsStateWithLifecycle()
    val giphyKey by viewModel.giphyKey.collectAsStateWithLifecycle()
    val gifTemplate by viewModel.gifSendTemplate.collectAsStateWithLifecycle()
    val commentGifsOn by viewModel.commentGifs.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by rememberSaveable { mutableStateOf<IntegrationsDialog?>(null) }
    var colourCategory by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.discordFailures.collect { snackbarHostState.showSnackbar(it) }
    }

    SettingsPage(
        title = stringResource(R.string.settings_integrations_title),
        onBack = onBack,
        highlight = highlight,
        snackbarHostState = snackbarHostState,
    ) {
        sponsorBlockSection(
            viewModel = viewModel,
            enabled = sponsorBlock,
            submitButton = submitButton,
            userId = userId,
            segments = segments,
            onEditUserId = { dialog = IntegrationsDialog.USER_ID },
            onPickColour = { colourCategory = it },
        )
        aiSection(
            viewModel = viewModel,
            enabled = aiEnabled,
            providerLabel = aiProvider.provider.label,
            hasKey = geminiKey.isNotBlank(),
            model = geminiModel,
            inspectorEnabled = inspectorOn,
            hasInspectorKey = inspectorKey.isNotBlank(),
            onEditKey = { dialog = IntegrationsDialog.GEMINI_KEY },
            onEditModel = { dialog = IntegrationsDialog.GEMINI_MODEL },
            onEditInspectorKey = { dialog = IntegrationsDialog.INSPECTOR_KEY },
        )
        group(key = "integrations.gif.group", header = R.string.gif_settings_header) {
            switch(IntegrationsIndex.commentGifs, viewModel.commentGifs, viewModel::setCommentGifs)
            switch(
                IntegrationsIndex.commentGifsWifiOnly,
                viewModel.commentGifsWifiOnly,
                viewModel::setCommentGifsWifiOnly,
                enabled = commentGifsOn,
            )
            row(IntegrationsIndex.giphyKey.key) { shape ->
                FlowNavRow(
                    title = stringResource(IntegrationsIndex.giphyKey.title),
                    supportingText =
                        stringResource(
                            when {
                                giphyKey.isNotBlank() -> R.string.flow_ai_key_set
                                BuildConfig.GIPHY_API_KEY.isNotBlank() -> R.string.gif_key_built_in
                                else -> R.string.flow_ai_key_not_set
                            },
                        ),
                    onClick = { dialog = IntegrationsDialog.GIPHY_KEY },
                    shape = shape,
                )
            }
            row(IntegrationsIndex.gifSendFormat.key) { shape ->
                FlowNavRow(
                    title = stringResource(IntegrationsIndex.gifSendFormat.title),
                    supportingText =
                        stringResource(if (gifTemplate.isBlank()) R.string.gif_format_default else R.string.gif_format_custom),
                    onClick = { dialog = IntegrationsDialog.GIF_FORMAT },
                    shape = shape,
                )
            }
        }
        group(key = "integrations.scrobbling.group", header = R.string.scrobbling_title) {
            nav(
                DestinationIndex.entry(SettingsDestination.SCROBBLING),
                icon = Icons.Outlined.GraphicEq,
                onClick = { onNavigate(SettingsTarget(SettingsDestination.SCROBBLING)) },
            )
        }
        group(key = "integrations.dearrow.group", header = R.string.player_settings_dearrow) {
            switch(IntegrationsIndex.deArrow, viewModel.deArrow, viewModel::setDeArrow, icon = Icons.Outlined.AutoFixHigh)
            switch(IntegrationsIndex.deArrowBadge, viewModel.deArrowBadge, viewModel::setDeArrowBadge, enabled = deArrow)
        }
        group(key = "integrations.dislikes.group", header = R.string.player_settings_rytd_title) {
            switch(IntegrationsIndex.dislikes, viewModel.dislikes, viewModel::setDislikes, icon = Icons.Outlined.ThumbDownOffAlt)
        }
        if (BuildConfig.UPDATER_ENABLED) {
            discordSection(
                state = discordState,
                onEnabledChange = viewModel::setDiscordEnabled,
                onConnect = { dialog = IntegrationsDialog.DISCORD_RISK },
                onUnlink = viewModel::unlinkDiscord,
                onRetry = viewModel::retryDiscord,
            )
        }
    }

    IntegrationsDialogs(
        dialog = dialog,
        userId = userId,
        geminiKey = geminiKey,
        geminiModel = geminiModel,
        inspectorKey = inspectorKey,
        giphyKey = giphyKey,
        gifTemplate = gifTemplate,
        viewModel = viewModel,
        onDismiss = { dialog = null },
    )
    colourCategory?.let { category ->
        SegmentColourDialog(
            category = category,
            current = segments[category]?.colorArgb,
            onSelect = { viewModel.setSegmentColor(category, it) },
            onDismiss = { colourCategory = null },
        )
    }
}
