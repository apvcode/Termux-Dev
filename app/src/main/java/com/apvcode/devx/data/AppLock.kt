package com.apvcode.devx.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import java.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The code of the app lock is kept as a salted hash, never as digits. */
object PinHasher {
    private const val ITERATIONS = 120_000

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    /** "salt:hash" in text, to be stored. */
    fun encode(pin: String, salt: ByteArray = newSalt(), iterations: Int = ITERATIONS): String =
        Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash(pin, salt, iterations)) + ":" + iterations

    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size < 2) return false
        val salt = runCatching { Base64.getDecoder().decode(parts[0]) }.getOrNull() ?: return false
        val expected = runCatching { Base64.getDecoder().decode(parts[1]) }.getOrNull() ?: return false
        val iterations = parts.getOrNull(2)?.toIntOrNull() ?: ITERATIONS
        // Compared in constant time: how long it takes says nothing about how close a guess was.
        return MessageDigest.isEqual(hash(pin, salt, iterations), expected)
    }

    /** How long (ms) the keypad stays shut after this many wrong codes in a row: nothing for the first few, then longer each time. */
    fun lockoutMs(fails: Int): Long = if (fails < 5) 0L else minOf(600_000L, 30_000L shl (fails - 5).coerceAtMost(5))
}

/** What the lock guards: only the chats that were locked, only the app when it opens, or both. */
enum class LockScope { Chats, App, Both }

/** What came of a code that was typed. */
sealed interface PinVerdict {
    data object Ok : PinVerdict
    data object Wrong : PinVerdict
    class Shut(val seconds: Int) : PinVerdict
}

/**
 * The lock of the app: a 6-digit code (and the fingerprint or the face, if the person wants), asked for when the app comes
 * back after a while and when a locked chat is opened. The code cannot be recovered: forgotten, it means installing the app again.
 */
object AppLock {
    const val LENGTH = 6
    private const val SECRET = "applock_pin"

    var enabled by mutableStateOf(false)
        private set
    var biometric by mutableStateOf(false)
        private set
    /** After how many seconds away the app locks itself (0: at once). */
    var timeoutSeconds by mutableIntStateOf(60)
        private set
    var scope by mutableStateOf(LockScope.Both)
        private set
    /** The app is shut right now: the lock screen covers it. */
    var locked by mutableStateOf(false)
    /** Since the app started the code has been entered at least once (until then the app behind the lock is not even drawn). */
    var everUnlocked by mutableStateOf(false)
        private set

    val locksApp: Boolean get() = enabled && scope != LockScope.Chats
    val locksChats: Boolean get() = enabled && scope != LockScope.App
    /** At the start of the app, while it is shut, nothing of it is drawn behind the lock. */
    /** The code was right and the padlock is opening: the app can be built behind the cover before the cover leaves. */
    var revealing by mutableStateOf(false)
    val hideContent: Boolean get() = locksApp && locked && !everUnlocked && !revealing
    private var shutUntil by mutableLongStateOf(0L)

    private var prefs: SharedPreferences? = null
    private var secrets: Secrets? = null
    private var leftAt = 0L
    private var fails = 0

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("applock", Context.MODE_PRIVATE)
        prefs = p
        secrets = Secrets(context.applicationContext)
        // The code lives in the keystore: a copy of the settings on another phone (or after a reinstall) has no code, so no lock.
        enabled = p.getBoolean("enabled", false) && secrets?.get(SECRET) != null
        biometric = enabled && p.getBoolean("biometric", false)
        timeoutSeconds = p.getInt("timeout", 60)
        scope = runCatching { LockScope.valueOf(p.getString("scope", "") ?: "") }.getOrDefault(LockScope.Both)
        fails = p.getInt("fails", 0)
        shutUntil = p.getLong("shutUntil", 0L)
        locked = locksApp
    }

    fun setPin(pin: String) {
        secrets?.put(SECRET, PinHasher.encode(pin))
        enabled = true
        fails = 0
        shutUntil = 0
        // Written at once (not later in the background): a lock that is on must still be on after the app is killed.
        prefs?.edit()?.putBoolean("enabled", true)?.putInt("fails", 0)?.putLong("shutUntil", 0)?.commit()
    }

    fun disable() {
        secrets?.remove(SECRET)
        enabled = false
        biometric = false
        locked = false
        prefs?.edit()?.putBoolean("enabled", false)?.putBoolean("biometric", false)?.commit()
    }

    fun useBiometric(on: Boolean) {
        biometric = on && enabled
        prefs?.edit()?.putBoolean("biometric", biometric)?.apply()
    }

    fun chooseScope(value: LockScope) {
        scope = value
        prefs?.edit()?.putString("scope", value.name)?.apply()
        if (!locksApp) locked = false
    }

    fun setTimeout(seconds: Int) {
        timeoutSeconds = seconds
        prefs?.edit()?.putInt("timeout", seconds)?.apply()
    }

    /** A typed code: right, wrong, or the keypad is shut for a while after too many wrong ones. */
    fun check(pin: String, now: Long = System.currentTimeMillis()): PinVerdict {
        if (now < shutUntil) return PinVerdict.Shut(((shutUntil - now + 999) / 1000).toInt())
        val stored = secrets?.get(SECRET) ?: return PinVerdict.Wrong
        if (PinHasher.matches(pin, stored)) {
            fails = 0
            shutUntil = 0
            prefs?.edit()?.putInt("fails", 0)?.putLong("shutUntil", 0)?.apply()
            return PinVerdict.Ok
        }
        fails++
        val wait = PinHasher.lockoutMs(fails)
        if (wait > 0) shutUntil = now + wait
        prefs?.edit()?.putInt("fails", fails)?.putLong("shutUntil", shutUntil)?.apply()
        return if (wait > 0) PinVerdict.Shut((wait / 1000).toInt()) else PinVerdict.Wrong
    }

    fun unlock() { locked = false; everUnlocked = true; revealing = false }

    /** Counts the "lock everything now" presses; the chats listen to it and close themselves. */
    var panic by mutableIntStateOf(0)

    /** Locks the app and every open chat at once. */
    fun lockNow() {
        if (!enabled) return
        if (locksApp) locked = true
        panic++
    }

    /** The app goes to the background. */
    fun leave() { leftAt = SystemClock.elapsedRealtime() }

    /** The app comes back: it locks if it was away for longer than the person allowed. */
    fun back() {
        if (!locksApp || locked) return
        if (timeoutSeconds == 0 || SystemClock.elapsedRealtime() - leftAt >= timeoutSeconds * 1000L) locked = true
    }
}
