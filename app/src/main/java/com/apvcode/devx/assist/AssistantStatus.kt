package com.apvcode.devx.assist

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.apvcode.devx.data.PermissionBridge

/** What the phone says about Devx as its assistant: asked afresh every time the settings come into view. */
object AssistantStatus {
    /** True when the power button and the corner swipe call Devx. */
    fun isDefault(context: Context): Boolean {
        val own = context.packageName + "/"
        val chosen = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant") ?: Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull()
        if (chosen != null) return chosen.startsWith(own)
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return roles.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        return false
    }

    fun micAllowed(context: Context) = PermissionBridge.has(context, Manifest.permission.RECORD_AUDIO)

    fun recognizerThere(context: Context) = SpeechRecognizer.isRecognitionAvailable(context)

    fun speechEngineThere(context: Context) =
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()

    /** The system's own question "make Devx your assistant?" when the phone has one, else the page of default apps. */
    fun chooseIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
