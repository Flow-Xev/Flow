package app.zaid.flow.ui.screens.settings.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.zaid.flow.R
import app.zaid.flow.data.ai.AiProvider
import app.zaid.flow.data.ai.AiProviderConfig
import app.zaid.flow.ui.components.shared.FlowAlertDialog
import app.zaid.flow.ui.theme.SponsorBlockSegmentPresets
import app.zaid.flow.utils.sponsorCategoryLabelRes

private val PresetSize = 32.dp
private val PresetBorder = 1.dp
private val PresetSpacing = 4.dp
private const val LIGHT_SWATCH_LUMINANCE = 0.5f

internal enum class IntegrationsDialog { USER_ID, DISCORD_RISK, GEMINI_KEY, GEMINI_MODEL, INSPECTOR_KEY, GIPHY_KEY, GIF_FORMAT }

@Composable
internal fun IntegrationsDialogs(
    dialog: IntegrationsDialog?,
    userId: String?,
    geminiKey: String,
    geminiModel: String,
    inspectorKey: String,
    giphyKey: String,
    gifTemplate: String,
    viewModel: IntegrationsViewModel,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        IntegrationsDialog.GEMINI_KEY -> {
            // Never prefilled: the saved key is not shown again, only replaced or removed.
            var input by rememberSaveable { mutableStateOf("") }
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("${viewModel.aiProvider.collectAsState().value.provider.label} API key") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(PresetSpacing)) {
                        Text(stringResource(R.string.flow_ai_key_dialog_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(stringResource(R.string.flow_ai_key_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            viewModel.setGeminiKey(input)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.btn_save)) }
                },
                dismissButton = {
                    Row {
                        if (geminiKey.isNotBlank()) {
                            TextButton(onClick = {
                                viewModel.setGeminiKey("")
                                onDismiss()
                            }) { Text(stringResource(R.string.flow_ai_key_remove)) }
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                    }
                },
            )
        }

        IntegrationsDialog.GIF_FORMAT -> {
            var input by rememberSaveable { mutableStateOf(gifTemplate) }
            val valid = app.zaid.flow.data.gif.GifSendFormat.isValid(input)
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.gif_format_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(PresetSpacing)) {
                        Text(stringResource(R.string.gif_format_dialog_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(stringResource(R.string.gif_format_hint)) },
                            isError = !valid,
                            minLines = 3,
                            maxLines = 8,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = valid,
                        onClick = {
                            viewModel.setGifSendTemplate(input)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.btn_save)) }
                },
                dismissButton = {
                    Row {
                        if (gifTemplate.isNotBlank()) {
                            TextButton(onClick = {
                                viewModel.setGifSendTemplate("")
                                onDismiss()
                            }) { Text(stringResource(R.string.gif_format_reset)) }
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                    }
                },
            )
        }

        IntegrationsDialog.GIPHY_KEY -> {
            var input by rememberSaveable { mutableStateOf("") }
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.gif_key_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(PresetSpacing)) {
                        Text(stringResource(R.string.gif_key_dialog_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(stringResource(R.string.flow_ai_key_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            viewModel.setGiphyKey(input)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.btn_save)) }
                },
                dismissButton = {
                    Row {
                        if (giphyKey.isNotBlank()) {
                            TextButton(onClick = {
                                viewModel.setGiphyKey("")
                                onDismiss()
                            }) { Text(stringResource(R.string.flow_ai_key_remove)) }
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                    }
                },
            )
        }

        IntegrationsDialog.INSPECTOR_KEY -> {
            var input by rememberSaveable { mutableStateOf("") }
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.flow_ai_inspector_key_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(PresetSpacing)) {
                        Text(stringResource(R.string.flow_ai_inspector_key_dialog_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(stringResource(R.string.flow_ai_key_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            viewModel.setInspectorKey(input)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.btn_save)) }
                },
                dismissButton = {
                    Row {
                        if (inspectorKey.isNotBlank()) {
                            TextButton(onClick = {
                                viewModel.setInspectorKey("")
                                onDismiss()
                            }) { Text(stringResource(R.string.flow_ai_key_remove)) }
                        }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
                    }
                },
            )
        }

        IntegrationsDialog.GEMINI_MODEL -> {
            val saved by viewModel.aiProvider.collectAsState()
            var provider by rememberSaveable { mutableStateOf(saved.provider) }
            var baseUrl by rememberSaveable { mutableStateOf(saved.baseUrl) }
            var web by rememberSaveable { mutableStateOf(saved.webSearch) }
            var noStore by rememberSaveable { mutableStateOf(saved.noDataStorage) }
            var fallbacks by rememberSaveable { mutableStateOf(saved.fallbacks) }
            var input by rememberSaveable { mutableStateOf(geminiModel) }
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("AI provider & model") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(PresetSpacing)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(PresetSpacing)) {
                            AiProvider.entries.forEach { p ->
                                FilterChip(
                                    selected = provider == p,
                                    onClick = {
                                        if (provider != p) {
                                            provider = p
                                            // Each provider keeps its own model; start from its default.
                                            input = if (p == saved.provider) geminiModel else p.defaultModel
                                        }
                                    },
                                    label = { Text(p.label) },
                                )
                            }
                        }
                        if (provider == AiProvider.CUSTOM) {
                            Text("Any OpenAI-compatible server. Use the root URL, e.g. https://host/v1", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("Base URL") },
                                singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(stringResource(R.string.flow_ai_model_hint)) },
                            singleLine = true,
                        )
                        if (provider == AiProvider.OPENROUTER) {
                            OutlinedTextField(
                                value = fallbacks,
                                onValueChange = { fallbacks = it },
                                label = { Text("Fallback models (comma separated)") },
                                singleLine = true,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Web search (:online)", Modifier.weight(1f))
                                Switch(checked = web, onCheckedChange = { web = it })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Only providers that don't store my data", Modifier.weight(1f))
                                Switch(checked = noStore, onCheckedChange = { noStore = it })
                            }
                            Text(
                                "Tools, prompt caching and long-context compression are always on.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (provider != saved.provider) {
                            Text("Each provider has its own API key; set it from the key row after saving.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = provider != AiProvider.CUSTOM || baseUrl.isNotBlank(),
                        onClick = {
                            viewModel.setAiProvider(AiProviderConfig(provider, baseUrl, web, noStore, fallbacks), input)
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.btn_save)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
            )
        }

        IntegrationsDialog.USER_ID -> {
            var input by rememberSaveable { mutableStateOf(userId.orEmpty()) }
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.sb_user_id_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(PresetSpacing)) {
                        Text(stringResource(R.string.sb_user_id_dialog_body), style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text(stringResource(R.string.sb_user_id_hint)) },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setUserId(input)
                        onDismiss()
                    }) { Text(stringResource(R.string.btn_save)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) } },
            )
        }

        IntegrationsDialog.DISCORD_RISK -> {
            FlowAlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.discord_presence_risk_dialog_title)) },
                text = { Text(stringResource(R.string.discord_presence_risk_dialog_body)) },
                confirmButton = {
                    Button(onClick = {
                        viewModel.connectDiscord()
                        onDismiss()
                    }) { Text(stringResource(R.string.discord_presence_risk_accept)) }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            )
        }

        null -> {
            Unit
        }
    }
}

/** Picks a segment colour from the presets, or goes back to the category's default. */
@Composable
internal fun SegmentColourDialog(
    category: String,
    current: Int?,
    onSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val label = sponsorCategoryLabelRes(category)?.let { stringResource(it) } ?: category
    FlowAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sb_color_picker_title)) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PresetSpacing),
                verticalArrangement = Arrangement.spacedBy(PresetSpacing),
            ) {
                SponsorBlockSegmentPresets.forEach { preset ->
                    val selected = preset.toArgb() == current
                    IconButton(
                        onClick = {
                            onSelect(preset.toArgb())
                            onDismiss()
                        },
                        modifier =
                            Modifier.semantics {
                                role = Role.RadioButton
                                this.selected = selected
                            },
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(PresetSize)
                                    .clip(CircleShape)
                                    .background(preset)
                                    .border(PresetBorder, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = stringResource(R.string.settings_segment_colour, label),
                                    tint = if (preset.luminance() > LIGHT_SWATCH_LUMINANCE) Color.Black else Color.White,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelect(null)
                onDismiss()
            }) { Text(stringResource(R.string.sb_color_reset)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
