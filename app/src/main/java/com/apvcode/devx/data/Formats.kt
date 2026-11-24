package com.apvcode.devx.data

import java.net.URI

/**
 * Two wire formats exist: OpenAI (`/v1/chat/completions`) and Anthropic (`/v1/messages`). A gateway
 * often serves both with ONE key and lists the Claude models only in the Anthropic list. Like devx,
 * the app asks such a gateway for its models in both formats and then sends each model in the
 * format that lists it. A provider that names Anthropic itself (api.anthropic.com, a `/anthropic/`
 * path, or chosen by hand) always uses the Anthropic format.
 */

/** The format a base URL names by itself, if it does. */
fun formatFromBaseUrl(baseUrl: String): ProviderKind? {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
    if (uri.host?.lowercase() == "api.anthropic.com") return ProviderKind.Anthropic
    if (uri.path.orEmpty().lowercase().split('/').contains("anthropic")) return ProviderKind.Anthropic
    return null
}

/** "https://host/v1" and "https://host" are the same gateway: both give the origin. Other paths give null. */
fun gatewayRoot(baseUrl: String): String? {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
    val rest = uri.path.orEmpty().trimEnd('/')
    if (rest != "" && rest != "/v1") return null
    val host = uri.host ?: return null
    return "${uri.scheme}://$host" + if (uri.port > 0) ":${uri.port}" else ""
}

/** Servers on this phone or in the local network are one-format servers: they are not probed. */
fun isLocalBaseUrl(baseUrl: String): Boolean {
    val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()?.lowercase() ?: return false
    return host == "localhost" || host.endsWith(".local") || host.startsWith("127.") || host.startsWith("10.") ||
        host.startsWith("192.168.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
}

/** Whether a provider is a plain gateway that may speak both formats. */
fun shouldProbeFormats(provider: Provider): Boolean =
    provider.kind == ProviderKind.OpenAI &&
        formatFromBaseUrl(provider.baseUrl) == null &&
        !isLocalBaseUrl(provider.baseUrl) &&
        detectRoute(provider.baseUrl) == Route.Generic &&
        gatewayRoot(provider.baseUrl) != null

/** The format a request for this model uses; decided without network access. */
fun formatOf(provider: Provider, model: String): ProviderKind {
    if (provider.kind == ProviderKind.Anthropic) return ProviderKind.Anthropic
    if (formatFromBaseUrl(provider.baseUrl) == ProviderKind.Anthropic) return ProviderKind.Anthropic
    // A model typed in by hand may name its format: only the provider itself can overrule it.
    provider.customOf(model)?.kind?.let { return it }
    if (provider.anthropicModels.isEmpty()) return ProviderKind.OpenAI
    val m = model.lowercase().trim()
    if (provider.anthropicModels.any { it.lowercase() == m }) return ProviderKind.Anthropic
    // A Claude model typed by hand on a gateway that lists Claude only in the Anthropic format.
    val openAiList = provider.models.filter { id -> provider.anthropicModels.none { it.equals(id, ignoreCase = true) } }
    if (m.startsWith("claude") && openAiList.none { it.contains("claude", ignoreCase = true) }) return ProviderKind.Anthropic
    return ProviderKind.OpenAI
}
