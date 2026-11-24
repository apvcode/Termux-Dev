package com.apvcode.devx.ui.screens

import com.apvcode.devx.ui.components.DevxIcons
import com.apvcode.devx.ui.components.pressable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apvcode.devx.R
import com.apvcode.devx.data.CustomModel
import com.apvcode.devx.data.ErrorKind
import com.apvcode.devx.data.Provider
import com.apvcode.devx.data.ProviderKind
import com.apvcode.devx.data.formatFromBaseUrl
import com.apvcode.devx.data.formatTokens
import com.apvcode.devx.ui.chat.ChatViewModel
import com.apvcode.devx.ui.components.ChoiceDialog
import com.apvcode.devx.ui.components.DevxDialog
import com.apvcode.devx.ui.components.DevxTextField
import com.apvcode.devx.ui.components.PillButton
import com.apvcode.devx.ui.components.ScreenScaffold
import com.apvcode.devx.ui.components.SectionLabel
import com.apvcode.devx.ui.components.SettingsDivider
import com.apvcode.devx.ui.components.SettingsGroup
import com.apvcode.devx.ui.components.SettingsRow
import com.apvcode.devx.ui.components.errorTitleRes
import com.apvcode.devx.ui.theme.DevxColors
import com.apvcode.devx.ui.theme.DevxType
import java.util.UUID
import androidx.compose.ui.text.input.KeyboardType
import java.net.URI

private const val OPENAI_URL = "https://api.openai.com/v1"
private const val ANTHROPIC_URL = "https://api.anthropic.com"

private class Preset(val name: String, val url: String)

private val presets = listOf(
    Preset("OpenAI", OPENAI_URL),
    Preset("Anthropic", ANTHROPIC_URL),
    Preset("OpenRouter", "https://openrouter.ai/api/v1"),
    Preset("DeepSeek", "https://api.deepseek.com/v1"),
    Preset("Groq", "https://api.groq.com/openai/v1"),
    Preset("Ollama", "http://localhost:11434/v1"),
)

@Composable
fun ProvidersScreen(vm: ChatViewModel, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    ScreenScaffold(stringResource(R.string.providers_title), onBack) {
        if (vm.providers.isEmpty()) {
            item { EmptyNote(stringResource(R.string.providers_empty)) }
        } else {
            item {
                SettingsGroup {
                    vm.providers.forEachIndexed { i, p ->
                        if (i > 0) SettingsDivider()
                        SettingsRow(
                            title = p.name,
                            subtitle = hostOf(p.baseUrl) + " · " + stringResource(R.string.provider_models_count, p.models.size) +
                                if (p.anthropicModels.isNotEmpty()) " · " + stringResource(R.string.provider_via_anthropic, p.anthropicModels.size) else "",
                            showChevron = true,
                            onClick = { onEdit(p.id) },
                        )
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.providers_add),
                    icon = Icons.Outlined.Add,
                    showChevron = true,
                    onClick = onAdd,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderEditScreen(vm: ChatViewModel, providerId: String?, onBack: () -> Unit) {
    val existing = vm.providers.firstOrNull { it.id == providerId }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: OPENAI_URL) }
    var key by remember { mutableStateOf("") }
    val refreshing = existing?.id?.let { vm.refreshing[it] } == true
    val failure = existing?.id?.let { vm.modelsError[it] }
    /** The model typed in by hand that is being added or changed now. */
    var editing by remember { mutableStateOf<CustomModel?>(null) }

    ScreenScaffold(
        title = stringResource(if (existing == null) R.string.provider_new else R.string.provider_edit),
        onBack = onBack,
    ) {
        if (existing == null) {
            item {
                SectionLabel(stringResource(R.string.provider_presets))
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        Chip(preset.name, selected = false) {
                            name = preset.name
                            baseUrl = preset.url
                        }
                    }
                }
            }
        }
        item { DevxTextField(stringResource(R.string.provider_name), name, { name = it }, placeholder = stringResource(R.string.provider_name_hint)) }
        item { DevxTextField(stringResource(R.string.provider_url), baseUrl, { baseUrl = it }, keyboardType = KeyboardType.Uri) }
        item {
            Text(
                stringResource(R.string.provider_kind_hint),
                style = DevxType.Caption,
                color = DevxColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        item {
            DevxTextField(
                stringResource(R.string.provider_key),
                key,
                { key = it },
                placeholder = if (existing != null) stringResource(R.string.provider_key_saved) else "",
                password = true,
            )
        }
        item {
            Text(
                stringResource(R.string.provider_key_hint),
                style = DevxType.Caption,
                color = DevxColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        item {
            PillButton(
                stringResource(R.string.provider_save),
                enabled = baseUrl.isNotBlank(),
                onClick = {
                    val id = existing?.id ?: UUID.randomUUID().toString()
                    val address = baseUrl.trim()
                    val kind = formatFromBaseUrl(address)
                        ?: if (existing?.kind == ProviderKind.Anthropic && existing.baseUrl == address) ProviderKind.Anthropic else ProviderKind.OpenAI
                    vm.saveProvider(
                        Provider(
                            id = id,
                            name = name.trim().ifBlank { hostOf(address) },
                            kind = kind,
                            baseUrl = address,
                            models = existing?.models.orEmpty(),
                            anthropicModels = existing?.anthropicModels.orEmpty(),
                            modelsUpdatedAt = existing?.modelsUpdatedAt ?: 0L,
                            listedModels = existing?.listedModels.orEmpty(),
                            customModels = existing?.customModels.orEmpty(),
                        ),
                        key.takeIf { it.isNotBlank() },
                    )
                    vm.refreshModels(id)
                    onBack()
                },
            )
        }

        if (existing != null) {
            val custom = existing.customModels
            item { SectionLabel(stringResource(R.string.provider_models)) }
            item {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(if (refreshing) R.string.provider_refreshing else R.string.provider_refresh),
                        subtitle = failure?.let { stringResource(errorTitleRes(it)) },
                        icon = if (refreshing) null else DevxIcons.Refresh,
                        onClick = if (refreshing) null else ({ vm.refreshModels(existing.id) }),
                        trailing = if (refreshing) {
                            { CircularProgressIndicator(Modifier.size(22.dp), color = DevxColors.TextPrimary, strokeWidth = 2.dp) }
                        } else null,
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.provider_models),
                        value = existing.models.size.toString(),
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.provider_model_add),
                        icon = Icons.Outlined.Add,
                        onClick = { editing = CustomModel(name = "", id = "") },
                    )
                }
            }
            if (custom.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.provider_models_custom)) }
                item {
                    SettingsGroup {
                        custom.forEachIndexed { i, m ->
                            if (i > 0) SettingsDivider()
                            SettingsRow(
                                title = m.name.ifBlank { m.id },
                                subtitle = buildString {
                                    append(m.id)
                                    m.context?.let { append(" · ").append(stringResource(R.string.provider_model_context_value, formatTokens(it))) }
                                    if (m.kind != null) append(" · ").append(stringResource(if (m.kind == ProviderKind.Anthropic) R.string.model_format_anthropic else R.string.model_format_openai))
                                },
                                showChevron = true,
                                onClick = { editing = m },
                            )
                        }
                    }
                }
            }
            val listed = existing.listedModels
            if (listed.isEmpty() && custom.isEmpty()) {
                item { EmptyNote(stringResource(R.string.provider_models_empty)) }
            } else if (listed.isNotEmpty()) {
                items(listed, key = { it }) { model ->
                    Text(
                        model,
                        style = DevxType.Body,
                        color = DevxColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.provider_delete),
                        titleColor = DevxColors.Danger,
                        onClick = {
                            vm.deleteProvider(existing.id)
                            onBack()
                        },
                    )
                }
            }
        }
    }
    val model = editing
    if (existing != null && model != null) {
        CustomModelDialog(
            model = model,
            onSave = { vm.saveCustomModel(existing.id, it); editing = null },
            onDelete = if (model.id.isNotEmpty() && existing.customModels.any { it.id == model.id }) {
                { vm.removeCustomModel(existing.id, model.id); editing = null }
            } else null,
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier
        .pressable(scaleTo = 0.92f, onClick = onClick)
        .clip(CircleShape)
        .let { if (selected) it.background(DevxColors.Accent) else it.border(1.dp, DevxColors.Outline, CircleShape) }
        .padding(horizontal = 16.dp, vertical = 10.dp)
    Text(
        text,
        style = DevxType.Body,
        color = if (selected) DevxColors.OnAccent else DevxColors.TextPrimary,
        modifier = modifier,
    )
}

/**
 * A model typed in by hand: the name shown, the id sent to the provider, and (optional) the context window and the format.
 * [onDelete] is given when the model is already saved.
 */
@Composable
private fun CustomModelDialog(model: CustomModel, onSave: (CustomModel) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(model.name) }
    var id by remember { mutableStateOf(model.id) }
    var context by remember { mutableStateOf(model.context?.toString().orEmpty()) }
    var kind by remember { mutableStateOf(model.kind) }
    var pickKind by remember { mutableStateOf(false) }
    // The id may not be empty; the context, when given, is a whole number of tokens.
    val contextTokens = context.trim().toIntOrNull()
    val ready = id.isNotBlank() && (context.isBlank() || (contextTokens != null && contextTokens > 0))
    if (pickKind) {
        ChoiceDialog(
            title = stringResource(R.string.provider_model_format),
            options = listOf(
                null to stringResource(R.string.provider_model_format_auto),
                ProviderKind.OpenAI to stringResource(R.string.model_format_openai),
                ProviderKind.Anthropic to stringResource(R.string.model_format_anthropic),
            ),
            selected = kind,
            onPick = { kind = it; pickKind = false },
            onDismiss = { pickKind = false },
        )
    }
    DevxDialog(stringResource(if (onDelete != null) R.string.provider_model_edit else R.string.provider_model_add), onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DevxTextField(stringResource(R.string.provider_model_name), name, { name = it }, placeholder = stringResource(R.string.provider_model_name_hint))
            DevxTextField(stringResource(R.string.provider_model_id), id, { id = it.trim() }, placeholder = stringResource(R.string.provider_model_id_hint))
            Text(stringResource(R.string.provider_model_id_note), style = DevxType.Caption, color = DevxColors.TextSecondary, modifier = Modifier.padding(start = 6.dp))
            DevxTextField(
                stringResource(R.string.provider_model_context), context, { context = it.filter { c -> c.isDigit() } },
                placeholder = stringResource(R.string.provider_model_context_hint), keyboardType = KeyboardType.Number,
            )
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.provider_model_format),
                    value = stringResource(
                        when (kind) {
                            null -> R.string.provider_model_format_auto
                            ProviderKind.OpenAI -> R.string.model_format_openai
                            ProviderKind.Anthropic -> R.string.model_format_anthropic
                        },
                    ),
                    onClick = { pickKind = true },
                )
            }
            PillButton(stringResource(R.string.provider_save), enabled = ready, modifier = Modifier.fillMaxWidth(), onClick = {
                onSave(CustomModel(name = name.trim(), id = id.trim(), context = contextTokens, kind = kind))
            })
            if (onDelete != null) {
                PillButton(stringResource(R.string.provider_model_delete), filled = false, modifier = Modifier.fillMaxWidth(), onClick = onDelete)
            }
        }
    }
}

private fun hostOf(url: String): String = runCatching { URI(url.trim()).host }.getOrNull() ?: url
