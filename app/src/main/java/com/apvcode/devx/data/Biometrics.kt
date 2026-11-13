package com.apvcode.devx.data

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat

/** The fingerprint or the face of the phone, through the system's own dialog (Android 10 and newer). */
object Biometrics {
    fun available(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        // A phone that refuses to answer (no permission, a broken service) simply has no fingerprint for us: the code is always there.
        return runCatching { manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS }.getOrDefault(false)
    }

    /** Shows the dialog; [onOk] when it is the right finger or face, [onOther] when the person chose the code instead or gave up. */
    fun prompt(context: Context, title: String, other: String, onOk: () -> Unit, onOther: () -> Unit): CancellationSignal? {
        if (!available(context)) { onOther(); return null }
        val executor = ContextCompat.getMainExecutor(context)
        val signal = CancellationSignal()
        val builder = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButton(other, executor) { _, _ -> onOther() }
        runCatching { builder.build().authenticate(signal, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { onOk() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { onOther() }
        }) }.onFailure { onOther() }
        return signal
    }
}
