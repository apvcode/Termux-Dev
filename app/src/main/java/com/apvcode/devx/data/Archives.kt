package com.apvcode.devx.data

import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Opening and making archives: zip (also jar, apk, aar), tar (plain, gz, bz2, xz), 7z, and single
 * compressed files (gz, bz2, xz). Entries that try to leave the target folder ("../") are refused, and so
 * is an archive that would unpack into more than [LIMIT] bytes.
 */
object Archives {
    const val LIMIT = 2L * 1024 * 1024 * 1024

    class Entry(val name: String, val size: Long, val directory: Boolean)

    private enum class Kind { Zip, Tar, TarGz, TarBz2, TarXz, SevenZ, Gz, Bz2, Xz }

    private fun kindOf(file: File): Kind? {
        val n = file.name.lowercase()
        return when {
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".aar") || n.endsWith(".apks") || n.endsWith(".xapk") ||
                n.endsWith(".docx") || n.endsWith(".xlsx") || n.endsWith(".pptx") || n.endsWith(".epub") -> Kind.Zip
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TarGz
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> Kind.TarBz2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TarXz
            n.endsWith(".tar") -> Kind.Tar
            n.endsWith(".7z") -> Kind.SevenZ
            n.endsWith(".gz") -> Kind.Gz
            n.endsWith(".bz2") -> Kind.Bz2
            n.endsWith(".xz") -> Kind.Xz
            else -> null
        }
    }

    fun supports(file: File) = kindOf(file) != null

    // ---------- reading ----------

    fun list(archive: File): List<Entry> {
        val out = ArrayList<Entry>()
        when (val kind = kindOf(archive) ?: unsupported(archive)) {
            Kind.SevenZ -> SevenZFile.builder().setFile(archive).get().use { z ->
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
                    out += Entry(e.name, e.size, e.isDirectory)
                    e = z.nextEntry
                }
            }
            Kind.Gz, Kind.Bz2, Kind.Xz -> out += Entry(singleName(archive), -1, false)
            else -> open(archive, kind).use { s ->
                var e: ArchiveEntry? = s.nextEntry
                while (e != null) {
                    out += Entry(e.name, e.size, e.isDirectory)
                    e = s.nextEntry
                }
            }
        }
        return out
    }

    /** Unpacks [archive] into [dest]; gives the number of files written. */
    fun extract(archive: File, dest: File): Int {
        dest.mkdirs()
        val root = dest.canonicalFile
        var total = 0L
        var count = 0

        fun target(name: String): File {
            val f = File(root, name).canonicalFile
            if (f.path != root.path && !f.path.startsWith(root.path + File.separator)) {
                throw IllegalArgumentException("The archive has an entry outside the target folder: $name")
            }
            return f
        }

        fun copy(input: InputStream, to: File) {
            to.parentFile?.mkdirs()
            FileOutputStream(to).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > LIMIT) throw IllegalStateException("The archive unpacks into more than ${LIMIT / 1_048_576} MB; stopped.")
                    out.write(buffer, 0, n)
                }
            }
            count++
        }

        when (val kind = kindOf(archive) ?: unsupported(archive)) {
            Kind.SevenZ -> SevenZFile.builder().setFile(archive).get().use { z ->
                var e: SevenZArchiveEntry? = z.nextEntry
                while (e != null) {
                    val to = target(e.name)
                    if (e.isDirectory) to.mkdirs() else copy(z.getInputStream(e), to)
                    e = z.nextEntry
                }
            }
            Kind.Gz, Kind.Bz2, Kind.Xz -> {
                val input = when (kind) {
                    Kind.Gz -> GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive)))
                    Kind.Bz2 -> BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
                    else -> XZCompressorInputStream(BufferedInputStream(FileInputStream(archive)))
                }
                input.use { copy(it, target(singleName(archive))) }
            }
            else -> open(archive, kind).use { s ->
                var e: ArchiveEntry? = s.nextEntry
                while (e != null) {
                    val to = target(e.name)
                    if (e.isDirectory) to.mkdirs() else copy(s, to)
                    e = s.nextEntry
                }
            }
        }
        return count
    }

    private fun open(archive: File, kind: Kind): ArchiveInputStream<out ArchiveEntry> {
        val raw = BufferedInputStream(FileInputStream(archive))
        return when (kind) {
            Kind.Zip -> ZipArchiveInputStream(raw)
            Kind.Tar -> TarArchiveInputStream(raw)
            Kind.TarGz -> TarArchiveInputStream(GzipCompressorInputStream(raw))
            Kind.TarBz2 -> TarArchiveInputStream(BZip2CompressorInputStream(raw))
            Kind.TarXz -> TarArchiveInputStream(XZCompressorInputStream(raw))
            else -> unsupported(archive)
        }
    }

    private fun singleName(archive: File) = archive.name.substringBeforeLast('.').ifBlank { "unpacked" }

    private fun unsupported(file: File): Nothing =
        throw IllegalArgumentException("Unknown archive type: ${file.name} (supported: .zip .jar .apk .tar .tar.gz .tgz .tar.bz2 .tar.xz .7z .gz .bz2 .xz)")

    // ---------- writing ----------

    /**
     * Makes [output] (the type comes from its name) out of [inputs]; each is stored under its path relative to
     * [base], folders with everything in them. Gives the number of files stored.
     */
    fun create(output: File, inputs: List<File>, base: File): Int {
        val kind = kindOf(output) ?: unsupported(output)
        output.parentFile?.mkdirs()
        val files = ArrayList<Pair<File, String>>()
        fun add(f: File) {
            val name = f.relativeToOrSelf(base).path.replace(File.separatorChar, '/')
            if (f.isDirectory) {
                f.listFiles()?.sortedBy { it.name }?.forEach { add(it) }
            } else if (f.isFile && f.canonicalPath != output.canonicalPath) {
                files += f to name
            }
        }
        inputs.forEach { add(it) }

        fun stream(wrap: (OutputStream) -> OutputStream) = wrap(BufferedOutputStream(FileOutputStream(output)))
        when (kind) {
            Kind.Zip -> ZipArchiveOutputStream(output).use { z ->
                for ((f, name) in files) {
                    z.putArchiveEntry(ZipArchiveEntry(f, name))
                    FileInputStream(f).use { it.copyTo(z) }
                    z.closeArchiveEntry()
                }
            }
            Kind.Tar, Kind.TarGz, Kind.TarBz2, Kind.TarXz -> {
                val sink = when (kind) {
                    Kind.TarGz -> stream { GzipCompressorOutputStream(it) }
                    Kind.TarBz2 -> stream { BZip2CompressorOutputStream(it) }
                    Kind.TarXz -> stream { XZCompressorOutputStream(it) }
                    else -> stream { it }
                }
                TarArchiveOutputStream(sink).use { t ->
                    t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    t.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for ((f, name) in files) {
                        t.putArchiveEntry(TarArchiveEntry(f, name))
                        FileInputStream(f).use { it.copyTo(t) }
                        t.closeArchiveEntry()
                    }
                }
            }
            Kind.SevenZ -> SevenZOutputFile(output).use { z ->
                for ((f, name) in files) {
                    z.putArchiveEntry(z.createArchiveEntry(f, name))
                    FileInputStream(f).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            z.write(buffer, 0, n)
                        }
                    }
                    z.closeArchiveEntry()
                }
            }
            Kind.Gz, Kind.Bz2, Kind.Xz -> {
                val single = files.singleOrNull() ?: throw IllegalArgumentException("A ${output.extension} file holds exactly one file; use .zip, .tar.gz or .7z for several.")
                val sink = when (kind) {
                    Kind.Gz -> stream { GzipCompressorOutputStream(it) }
                    Kind.Bz2 -> stream { BZip2CompressorOutputStream(it) }
                    else -> stream { XZCompressorOutputStream(it) }
                }
                sink.use { out -> FileInputStream(single.first).use { it.copyTo(out) } }
            }
        }
        return files.size
    }
}
