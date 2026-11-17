package com.apvcode.devx.data

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one way the app writes its own data files: to a temporary file beside it, then renamed over it, one writer at a time (two
 * writers sharing the same temporary file could rename it away from each other, and the loser then deleted the real file). A crash
 * in the middle leaves the old file whole.
 */
object AtomicFile {
    private val lock = Any()

    fun write(file: File, text: String) = write(file, text.toByteArray())

    fun write(file: File, bytes: ByteArray) = synchronized(lock) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** The same, but a failure is noted in the [ErrorLog] and not thrown (for writes that run in the background). */
    fun writeQuietly(tag: String, file: File, text: String): Boolean = try { write(file, text); true } catch (e: Exception) { ErrorLog.warn(tag, "could not write ${file.name}", e); false }
}

/**
 * What went wrong inside the app and was recovered from (a file that could not be read, a write that failed): kept as the last few
 * lines, shown under "About" so that it can be copied, and written to the system log. Nothing is sent anywhere.
 */
object ErrorLog {
    private const val KEEP = 60
    private val lines = ArrayDeque<String>()

    @Volatile var count = 0
        private set

    private var lastKey = ""
    private var lastRepeats = 1

    fun warn(tag: String, message: String, error: Throwable? = null) {
        val key = "[" + tag + "] " + message + (error?.let { " — " + it.javaClass.simpleName + ": " + it.message?.take(160) }.orEmpty())
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        synchronized(lines) {
            count++
            // The same thing failing again and again (a retry loop) is one line with a number, so it does not push the others out.
            if (key == lastKey && lines.isNotEmpty()) {
                lastRepeats++
                lines.removeLast()
                lines.addLast("$stamp $key (×$lastRepeats)")
            } else {
                lastKey = key; lastRepeats = 1
                lines.addLast("$stamp $key")
                while (lines.size > KEEP) lines.removeFirst()
            }
        }
        try { Log.w("devx-$tag", message, error) } catch (_: Throwable) {}
    }

    fun recent(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear(); count = 0; lastKey = ""; lastRepeats = 1 }
}

/**
 * Runs [block]; a failure is noted in the [ErrorLog] under [tag] and does not stop the caller. It is what a bare "runCatching" that threw
 * the error away should have been: the work that failed is still skipped, but it is no longer invisible.
 */
inline fun attempt(tag: String, block: () -> Unit) {
    try { block() } catch (e: Exception) { ErrorLog.warn(tag, "a step failed and was skipped", e) }
}

/**
 * Notes a failure of a [runCatching] in the [ErrorLog] and gives the result on, so that the code after it works as before
 * (`getOrNull()`, `getOrDefault(..)`, `isSuccess`). A cancelled coroutine is not an error and is not noted.
 */
fun <T> Result<T>.logged(tag: String, what: String = "a step failed"): Result<T> {
    val e = exceptionOrNull() ?: return this
    if (e !is kotlinx.coroutines.CancellationException) ErrorLog.warn(tag, what, e)
    return this
}
