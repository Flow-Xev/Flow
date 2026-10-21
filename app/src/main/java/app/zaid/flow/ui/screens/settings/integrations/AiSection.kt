package app.zaid.flow.ui.screens.settings.integrations

import androidx.compose.ui.res.stringResource
import app.zaid.flow.R
import app.zaid.flow.ui.components.settings.SettingsListScope
import app.zaid.flow.ui.components.settings.switch
import app.zaid.flow.ui.components.shared.FlowNavRow
import app.zaid.flow.ui.screens.settings.index.IntegrationsIndex

/** The Gemini key and model the Flow AI assistant runs on. */
internal fun SettingsListScope.aiSection(
    viewModel: IntegrationsViewModel,
    enabled: Boolean,
    hasKey: Boolean,
    model: String,
    inspectorEnabled: Boolean,
    hasInspectorKey: Boolean,
    onEditKey: () -> Unit,
    onEditModel: () -> Unit,
    onEditInspectorKey: () -> Unit,
) {
    group(key = "integrations.ai.group", header = R.string.flow_ai_settings_header) {
        switch(IntegrationsIndex.aiEnabled, viewModel.aiEnabled, viewModel::setAiEnabled)
        switch(IntegrationsIndex.aiChapters, viewModel.aiChapters, viewModel::setAiChapters, enabled = enabled)
        row(IntegrationsIndex.geminiKey.key) { shape ->
            FlowNavRow(
                title = stringResource(IntegrationsIndex.geminiKey.title),
                supportingText = stringResource(if (hasKey) R.string.flow_ai_key_set else R.string.flow_ai_key_not_set),
                enabled = enabled,
                onClick = onEditKey,
                shape = shape,
            )
        }
        row(IntegrationsIndex.geminiModel.key) { shape ->
            FlowNavRow(
                title = stringResource(IntegrationsIndex.geminiModel.title),
                supportingText = model,
                enabled = enabled,
                onClick = onEditModel,
                shape = shape,
            )
        }
        switch(IntegrationsIndex.aiInspector, viewModel.aiInspector, viewModel::setAiInspector, enabled = enabled)
        row(IntegrationsIndex.inspectorKey.key) { shape ->
            FlowNavRow(
                title = stringResource(IntegrationsIndex.inspectorKey.title),
                supportingText =
                    stringResource(
                        if (hasInspectorKey) R.string.flow_ai_key_set else R.string.flow_ai_inspector_key_not_set,
                    ),
                enabled = enabled && inspectorEnabled,
                onClick = onEditInspectorKey,
                shape = shape,
            )
        }
    }
}
