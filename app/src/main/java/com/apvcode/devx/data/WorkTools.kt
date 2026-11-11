package com.apvcode.devx.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.android.apksig.ApkSigner
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The assistant's workshop: it writes files, hands them over as cards, unpacks and makes archives and APKs,
 * edits pictures, and (when the person has allowed it) runs shell commands and Python. All of it happens in
 * the folder of the chat; paths are given relative to it.
 */
class WorkTools(
    private val context: Context,
    private val workspace: Workspace,
    private val artifacts: ArtifactStore,
) {
    fun definitions(files: Boolean, terminal: Boolean): List<WireTool> {
        val out = ArrayList<WireTool>()
        if (files) {
            out += tool(
                "create_file",
                "Make a file and hand it to the person as a card in your answer (they can open it to read the code, download it or share it). " +
                    "Use it whenever the person wants a file: a script, a config, a document, a data file. Do not paste long code into the answer when a file is wanted.",
                schema("name" to str("File name with extension, for example main.py."), "content" to str("The whole text of the file.")),
            )
            out += tool(
                "attach_file",
                "Hand a file from your working folder to the person as a card (an archive you made, a picture you edited, anything).",
                schema("path" to str("Path in your working folder."), "title" to str("Name to show (default: the file's own)."), required = listOf("path")),
            )
            out += tool(
                "write_file",
                "Write a text file in your working folder (not shown to the person; use create_file for that).",
                schema("path" to str("Path in your working folder; folders are made as needed."), "content" to str("The text.")),
            )
            out += tool(
                "read_file",
                "Read a text file from your working folder. Gives up to 20000 characters from the offset.",
                schema("path" to str("Path."), "offset" to int("Start, in characters (default 0)."), "length" to int("How many (default 12000)."), required = listOf("path")),
            )
            out += tool(
                "list_dir",
                "List a folder of your working folder (default: the folder itself). The files the person attached are in uploads/.",
                schema("path" to str("Folder (default \".\")."), required = emptyList()),
            )
            out += tool(
                "archive_list",
                "List what is inside an archive: .zip .jar .apk .tar .tar.gz .tgz .tar.bz2 .tar.xz .7z .gz .bz2 .xz",
                schema("path" to str("The archive.")),
            )
            out += tool(
                "archive_extract",
                "Unpack an archive into a folder (default: a folder named like the archive).",
                schema("path" to str("The archive."), "dest" to str("Folder to unpack into."), required = listOf("path")),
            )
            out += tool(
                "archive_create",
                "Make an archive from files and folders. The type follows the name: .zip .tar .tar.gz .tgz .tar.bz2 .tar.xz .7z (.gz .bz2 .xz hold a single file).",
                schema("output" to str("The archive to make, for example out/project.zip."), "paths" to strList("What to put in it: files and folders.")),
            )
            out += tool(
                "apk_info",
                "Details of an APK: package name, version, SDK levels, permissions, activities, services.",
                schema("path" to str("The .apk file.")),
            )
            out += tool(
                "apk_unpack",
                "Unpack an APK into a folder (its files, as in any zip; the manifest stays in Android's binary form) and write apk-info.txt next to them.",
                schema("path" to str("The .apk file."), "dest" to str("Folder to unpack into."), required = listOf("path")),
            )
            out += tool(
                "apk_pack",
                "Pack a folder into an APK and sign it (v1, v2, v3) with the app's own key, so that it can be installed. " +
                    "Use it for an unpacked APK after its files (assets, resources files, libraries) were changed. A binary AndroidManifest.xml or resources.arsc cannot be rewritten from text here.",
                schema("dir" to str("The folder (an unpacked APK)."), "output" to str("The .apk to make.")),
            )
            out += tool(
                "image_edit",
                "Edit a picture: resize, crop, rotate, flip, turn grey, change type (jpg, png, webp) and quality. Steps run in this order: crop, rotate, flip, grayscale, resize.",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put("input", str("The picture."))
                            .put("output", str("Where to save (the type follows the extension: .jpg .png .webp)."))
                            .put("width", int("New width in pixels."))
                            .put("height", int("New height in pixels."))
                            .put("mode", str("With both width and height: \"fit\" keeps proportions inside the box (default), \"fill\" fills the box and cuts the rest, \"stretch\" distorts."))
                            .put("crop", JSONObject().put("type", "object").put("description", "Cut out a part: x, y, width, height in pixels.")
                                .put("properties", JSONObject().put("x", int("Left.")).put("y", int("Top.")).put("width", int("Width.")).put("height", int("Height."))))
                            .put("rotate", int("Degrees clockwise (90, 180, 270 or any)."))
                            .put("flip", str("\"horizontal\" or \"vertical\"."))
                            .put("grayscale", JSONObject().put("type", "boolean").put("description", "Turn the picture grey."))
                            .put("quality", int("1 to 100 for jpg and webp (default 90).")),
                    )
                    .put("required", JSONArray(listOf("input", "output"))),
            )
        }
        if (terminal) {
            out += tool(
                "shell",
                "Run a shell command (Android's sh with its tools: ls cp mv rm mkdir cat grep sed awk find sort head tail wc tar gzip bzip2 unzip base64 md5sum sha256sum xargs and the like) " +
                    "in your working folder. There is no python, curl, zip or package manager here; use the python tool and the archive tools. The output is cut at 20000 characters.",
                schema("command" to str("The command line."), "timeout" to int("Seconds before it is stopped (default 60, at most 300)."), required = listOf("command")),
            )
            out += tool(
                "python",
                "Run Python 3.13 code in your working folder and get what it printed. The standard library is there, with Pillow, numpy, lxml, openpyxl, XlsxWriter, python-docx, python-pptx, pypdf, reportlab and imageio, and what pip_install added. Use print() to see results. Files you make can be handed over with attach_file.",
                schema("code" to str("The code."), "timeout" to int("Seconds before it is stopped (default 60, at most 300)."), required = listOf("code")),
            )
            out += tool(
                "pip_install",
                "Install one more Python package for the python tool. Only packages made of pure Python can be installed (not ones with native code). The common ones for documents and pictures are already there.",
                schema("package" to str("The name on PyPI.")),
            )
        }
        return out
    }

    val names: Set<String> = setOf(
        "create_file", "attach_file", "write_file", "read_file", "list_dir", "archive_list", "archive_extract", "archive_create",
        "apk_info", "apk_unpack", "apk_pack", "image_edit", "shell", "python", "pip_install",
    )

    suspend fun run(name: String, args: JSONObject, chatId: String, report: ToolReport): ToolOutcome {
        val dir = workspace.dirOf(chatId)
        fun path(key: String): File? = workspace.resolve(chatId, args.optString(key))
        fun bad(key: String) = ToolOutcome("The path \"${args.optString(key)}\" is outside your working folder.")
        return when (name) {
            "shell" -> {
                report.activity(ToolActivity(ToolKind.File, args.optString("command").take(60)))
                ToolOutcome(shell(args.optString("command"), dir, args.optInt("timeout", 60).coerceIn(1, 300)))
            }
            "python" -> {
                report.activity(ToolActivity(ToolKind.File, "Python"))
                ToolOutcome(python(args.optString("code"), dir, args.optInt("timeout", 60).coerceIn(1, 300)))
            }
            "pip_install" -> {
                report.activity(ToolActivity(ToolKind.File, args.optString("package")))
                ToolOutcome(pip(args.optString("package"), dir))
            }
            else -> withContext(Dispatchers.IO) {
                report.activity(ToolActivity(ToolKind.File, args.optString("path").ifBlank { args.optString("name").ifBlank { args.optString("input") } }))
                when (name) {
                    "create_file" -> createFile(args.optString("name"), args.optString("content"), dir, report)
                    "attach_file" -> attach(path("path") ?: return@withContext bad("path"), args.optString("title"), report)
                    "write_file" -> writeFile(path("path") ?: return@withContext bad("path"), args.optString("content"))
                    "read_file" -> readFile(path("path") ?: return@withContext bad("path"), args.optInt("offset", 0), args.optInt("length", 12_000))
                    "list_dir" -> listDir(path("path") ?: return@withContext bad("path"), dir)
                    "archive_list" -> archiveList(path("path") ?: return@withContext bad("path"))
                    "archive_extract" -> {
                        val archive = path("path") ?: return@withContext bad("path")
                        val dest = if (args.optString("dest").isBlank()) File(archive.parentFile, archive.name.substringBefore('.')) else workspace.resolve(chatId, args.optString("dest")) ?: return@withContext bad("dest")
                        archiveExtract(archive, dest, dir)
                    }
                    "archive_create" -> {
                        val output = path("output") ?: return@withContext bad("output")
                        val list = args.optJSONArray("paths") ?: JSONArray()
                        val inputs = (0 until list.length()).map { workspace.resolve(chatId, list.getString(it)) ?: return@withContext ToolOutcome("The path \"${list.getString(it)}\" is outside your working folder.") }
                        archiveCreate(output, inputs, dir)
                    }
                    "apk_info" -> apkInfo(path("path") ?: return@withContext bad("path"))
                    "apk_unpack" -> {
                        val apk = path("path") ?: return@withContext bad("path")
                        val dest = if (args.optString("dest").isBlank()) File(apk.parentFile, apk.nameWithoutExtension) else workspace.resolve(chatId, args.optString("dest")) ?: return@withContext bad("dest")
                        apkUnpack(apk, dest, dir)
                    }
                    "apk_pack" -> apkPack(path("dir") ?: return@withContext bad("dir"), path("output") ?: return@withContext bad("output"), dir)
                    "image_edit" -> imageEdit(path("input") ?: return@withContext bad("input"), path("output") ?: return@withContext bad("output"), args, dir)
                    else -> ToolOutcome("Unknown tool: $name")
                }
            }
        }
    }

    // ---------- files ----------

    private fun rel(file: File, dir: File) = file.toPath().toAbsolutePath().normalize().let { dir.toPath().toAbsolutePath().normalize().relativize(it).toString() }

    private fun createFile(name: String, content: String, dir: File, report: ToolReport): ToolOutcome {
        if (name.isBlank()) return ToolOutcome("Give the file a name.")
        val file = File(dir, "created/" + name.substringAfterLast('/'))
        file.parentFile?.mkdirs()
        file.writeText(content)
        val artifact = artifacts.add(file, file.name)
        report.artifact(artifact)
        return ToolOutcome("Created ${artifact.name} (${artifact.size} bytes) and handed it over as a card. It is also at created/${file.name} in your working folder.")
    }

    private fun attach(file: File, title: String, report: ToolReport): ToolOutcome {
        if (!file.isFile) return ToolOutcome("There is no such file: ${file.name}.")
        if (file.length() > MAX_ARTIFACT) return ToolOutcome("The file is too large to hand over (over ${MAX_ARTIFACT / 1_048_576} MB).")
        val artifact = artifacts.add(file, title.ifBlank { file.name })
        report.artifact(artifact)
        return ToolOutcome("Handed over ${artifact.name} (${artifact.size} bytes) as a card.")
    }

    private fun writeFile(file: File, content: String): ToolOutcome {
        if (content.length > MAX_WRITE) return ToolOutcome("The text is too long for one file write.")
        file.parentFile?.mkdirs()
        file.writeText(content)
        return ToolOutcome("Wrote ${file.length()} bytes to ${file.name}.")
    }

    private fun readFile(file: File, offset: Int, length: Int): ToolOutcome {
        if (!file.isFile) return ToolOutcome("There is no such file: ${file.name}.")
        val head = file.inputStream().use { it.readNBytes(MAX_READ_BYTES) }
        if (head.take(4096).any { it.toInt() == 0 }) return ToolOutcome("${file.name} is a binary file (${file.length()} bytes), not text.")
        val text = String(head, Charsets.UTF_8)
        val start = offset.coerceIn(0, text.length)
        val end = (start + length.coerceIn(1, 20_000)).coerceAtMost(text.length)
        val more = if (end < text.length) "\n[goes on from offset $end]" else if (file.length() > MAX_READ_BYTES) "\n[the file is longer than what can be read this way]" else "\n[end]"
        return ToolOutcome("Characters $start to $end of ${text.length} of ${file.name}:\n" + text.substring(start, end) + more)
    }

    private fun listDir(folder: File, root: File): ToolOutcome {
        if (!folder.isDirectory) return ToolOutcome("That is not a folder.")
        val lines = ArrayList<String>()
        fun walk(f: File, depth: Int) {
            f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))?.forEach { child ->
                if (lines.size >= 300) return
                val target = if (java.nio.file.Files.isSymbolicLink(child.toPath())) " (link)" else ""
                lines += rel(child, root) + if (child.isDirectory) "/" else "  ${child.length()} bytes$target"
                if (child.isDirectory && depth < 2) walk(child, depth + 1)
            }
        }
        walk(folder, 0)
        return ToolOutcome(if (lines.isEmpty()) "(empty)" else lines.joinToString("\n") + if (lines.size >= 300) "\n[the list is cut]" else "")
    }

    // ---------- archives ----------

    private fun archiveList(archive: File): ToolOutcome = runCatching {
        val entries = Archives.list(archive)
        ToolOutcome(
            "${archive.name}: ${entries.size} entries\n" + entries.take(400).joinToString("\n") { (if (it.directory) "dir  " else "file ") + it.name + if (it.size >= 0 && !it.directory) "  ${it.size} bytes" else "" } +
                if (entries.size > 400) "\n[the list is cut]" else "",
        )
    }.getOrElse { ToolOutcome("Could not read the archive: ${it.message}") }

    private fun archiveExtract(archive: File, dest: File, root: File): ToolOutcome = runCatching {
        val count = Archives.extract(archive, dest)
        ToolOutcome("Unpacked $count files into ${rel(dest, root)}/")
    }.getOrElse { ToolOutcome("Could not unpack: ${it.message}") }

    private fun archiveCreate(output: File, inputs: List<File>, root: File): ToolOutcome = runCatching {
        val count = Archives.create(output, inputs, root)
        ToolOutcome("Made ${rel(output, root)} (${output.length()} bytes) with $count files.")
    }.getOrElse { ToolOutcome("Could not make the archive: ${it.message}") }

    // ---------- APK ----------

    private fun apkInfo(apk: File): ToolOutcome {
        if (!apk.isFile) return ToolOutcome("There is no such file: ${apk.name}.")
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val info = context.packageManager.getPackageArchiveInfo(apk.path, flags) ?: return ToolOutcome("${apk.name} is not a readable APK.")
        val app = info.applicationInfo
        return ToolOutcome(
            buildString {
                append("Package: ").append(info.packageName).append('\n')
                append("Version: ").append(info.versionName).append(" (").append(info.longVersionCode).append(")\n")
                if (app != null) append("SDK: min ").append(app.minSdkVersion).append(", target ").append(app.targetSdkVersion).append('\n')
                append("Size: ").append(apk.length()).append(" bytes\n")
                info.requestedPermissions?.let { append("Permissions (").append(it.size).append("):\n  ").append(it.joinToString("\n  ")).append('\n') }
                info.activities?.let { append("Activities: ").append(it.joinToString { a -> a.name }).append('\n') }
                info.services?.let { append("Services: ").append(it.joinToString { a -> a.name }).append('\n') }
                info.receivers?.let { append("Receivers: ").append(it.joinToString { a -> a.name }).append('\n') }
                info.providers?.let { append("Providers: ").append(it.joinToString { a -> a.name }).append('\n') }
            },
        )
    }

    private fun apkUnpack(apk: File, dest: File, root: File): ToolOutcome = runCatching {
        val count = Archives.extract(apk, dest)
        val info = (apkInfo(apk).text)
        File(dest, "apk-info.txt").writeText(info)
        ToolOutcome("Unpacked $count files into ${rel(dest, root)}/ (apk-info.txt has the details).")
    }.getOrElse { ToolOutcome("Could not unpack the APK: ${it.message}") }

    private fun apkPack(folder: File, output: File, root: File): ToolOutcome = runCatching {
        if (!folder.isDirectory) return@runCatching ToolOutcome("There is no such folder.")
        output.parentFile?.mkdirs()
        val unsigned = File(context.cacheDir, "unsigned-${System.nanoTime()}.apk")
        try {
            writeAligned(folder, unsigned)
            val (key, cert) = signingKey()
            val signer = ApkSigner.SignerConfig.Builder("devx", key, listOf(cert)).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()
        } finally {
            unsigned.delete()
        }
        ToolOutcome("Made ${rel(output, root)} (${output.length()} bytes), signed with the app's key. It can be installed over nothing else than an app with the same key, or fresh.")
    }.getOrElse {
        output.delete()
        ToolOutcome("Could not make the APK: ${it.message}" + (generateSequence(it.cause) { c -> c.cause }.joinToString("") { c -> " / " + c.javaClass.simpleName + ": " + c.message }))
    }

    /** A zip of the folder in which resources.arsc is stored plain and aligned to four bytes, as Android requires. */
    private fun writeAligned(folder: File, target: File) {
        val files = folder.walkTopDown().filter { it.isFile && !it.relativeTo(folder).path.startsWith("META-INF") && it.name != "apk-info.txt" }.toList()
        val count = object : java.io.FilterOutputStream(FileOutputStream(target)) {
            var written = 0L
            override fun write(b: Int) { out.write(b); written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
        }
        ZipOutputStream(count).use { zip ->
            for (f in files) {
                val name = f.relativeTo(folder).path.replace(File.separatorChar, '/')
                val bytes = f.readBytes()
                val entry = ZipEntry(name)
                if (name == "resources.arsc" || name.endsWith(".so")) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().also { it.update(bytes) }.value
                    // The data must start at a multiple of 4: pad with an extra field of the right length.
                    val headerEnd = count.written + 30 + name.toByteArray().size
                    val missing = ((4 - headerEnd % 4) % 4).toInt()
                    if (missing > 0) entry.extra = ByteArray(4 + missing).also { it[0] = 0x35; it[1] = 0xD9.toByte(); it[2] = missing.toByte() }
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /**
     * The key APKs are signed with: made on first use, kept in the app's private folder (no other app can read it).
     * It is an ordinary RSA key with a self-signed certificate, which is what an APK needs.
     */
    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val keyFile = File(context.filesDir, "apk-signing-key.p8")
        val certFile = File(context.filesDir, "apk-signing-cert.der")
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        if (!keyFile.isFile || !certFile.isFile) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = selfSigned(pair)
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(cert)
        }
        val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = factory.generateCertificate(certFile.inputStream()) as X509Certificate
        return key to cert
    }

    /** A self-signed X.509 certificate (valid 25 years, subject CN=Devx), built by hand in DER. */
    private fun selfSigned(pair: java.security.KeyPair): ByteArray {
        fun len(n: Int): ByteArray = when {
            n < 128 -> byteArrayOf(n.toByte())
            n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        fun der(tag: Int, vararg parts: ByteArray): ByteArray {
            val body = parts.fold(ByteArray(0)) { a, b -> a + b }
            return byteArrayOf(tag.toByte()) + len(body.size) + body
        }
        val sha256Rsa = der(0x30, der(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)), der(0x05))
        val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)), der(0x0C, "Devx".toByteArray()))))
        fun time(c: Calendar): ByteArray = der(0x17, java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(c.time).toByteArray())
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 25) }
        val serial = der(0x02, java.math.BigInteger.valueOf(System.currentTimeMillis()).toByteArray())
        val tbs = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))),
            serial, sha256Rsa, name, der(0x30, time(start), time(end)), name, pair.public.encoded,
        )
        val signer = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }
        val signature = signer.sign()
        return der(0x30, tbs, sha256Rsa, der(0x03, byteArrayOf(0) + signature))
    }

    // ---------- pictures ----------

    private fun imageEdit(input: File, output: File, args: JSONObject, root: File): ToolOutcome = runCatching {
        if (!input.isFile) return@runCatching ToolOutcome("There is no such picture: ${input.name}.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.path, bounds)
        if (bounds.outWidth <= 0) return@runCatching ToolOutcome("${input.name} is not a picture that can be read.")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 6000) sample *= 2
        var bitmap = BitmapFactory.decodeFile(input.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching ToolOutcome("The picture could not be decoded.")

        // Turn upright by what the camera wrote, so that later steps work on what the person sees.
        val orientation = runCatching { ExifInterface(input.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull()
        val upright = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> upright.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> upright.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> upright.postRotate(270f)
        }
        if (!upright.isIdentity) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, upright, true)

        args.optJSONObject("crop")?.let { c ->
            val x = c.optInt("x", 0).coerceIn(0, bitmap.width - 1)
            val y = c.optInt("y", 0).coerceIn(0, bitmap.height - 1)
            val w = c.optInt("width", bitmap.width - x).coerceIn(1, bitmap.width - x)
            val h = c.optInt("height", bitmap.height - y).coerceIn(1, bitmap.height - y)
            bitmap = Bitmap.createBitmap(bitmap, x, y, w, h)
        }
        val turn = args.optInt("rotate", 0)
        val flip = args.optString("flip")
        if (turn % 360 != 0 || flip.isNotEmpty()) {
            val m = Matrix()
            if (turn % 360 != 0) m.postRotate(turn.toFloat())
            if (flip.startsWith("h")) m.postScale(-1f, 1f)
            if (flip.startsWith("v")) m.postScale(1f, -1f)
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        if (args.optBoolean("grayscale", false)) {
            val grey = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }) }
            android.graphics.Canvas(grey).drawBitmap(bitmap, 0f, 0f, paint)
            bitmap = grey
        }
        val wantW = args.optInt("width", 0)
        val wantH = args.optInt("height", 0)
        if (wantW > 0 || wantH > 0) {
            val mode = args.optString("mode", "fit")
            val sw = bitmap.width.toFloat()
            val sh = bitmap.height.toFloat()
            val (nw, nh) = when {
                wantW > 0 && wantH > 0 && mode == "stretch" -> wantW to wantH
                wantW > 0 && wantH > 0 && mode == "fill" -> maxOf(wantW / sw, wantH / sh).let { (sw * it).toInt() to (sh * it).toInt() }
                wantW > 0 && wantH > 0 -> minOf(wantW / sw, wantH / sh).let { (sw * it).toInt() to (sh * it).toInt() }
                wantW > 0 -> wantW to (sh * wantW / sw).toInt()
                else -> (sw * wantH / sh).toInt() to wantH
            }
            bitmap = Bitmap.createScaledBitmap(bitmap, nw.coerceIn(1, 12_000), nh.coerceIn(1, 12_000), true)
            if (wantW > 0 && wantH > 0 && mode == "fill") {
                bitmap = Bitmap.createBitmap(bitmap, ((bitmap.width - wantW) / 2).coerceAtLeast(0), ((bitmap.height - wantH) / 2).coerceAtLeast(0), minOf(wantW, bitmap.width), minOf(wantH, bitmap.height))
            }
        }

        output.parentFile?.mkdirs()
        val quality = args.optInt("quality", 90).coerceIn(1, 100)
        val format = when (output.extension.lowercase()) {
            "png" -> Bitmap.CompressFormat.PNG
            "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        // A picture with see-through parts saved as JPEG would get black there: put it on white.
        if (format == Bitmap.CompressFormat.JPEG && bitmap.hasAlpha()) {
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            bitmap = flat
        }
        FileOutputStream(output).use { bitmap.compress(format, quality, it) }
        ToolOutcome("Saved ${rel(output, root)}: ${bitmap.width}x${bitmap.height}, ${output.length()} bytes.")
    }.getOrElse { ToolOutcome("Could not edit the picture: ${it.message}") }

    // ---------- the terminal ----------

    private suspend fun shell(command: String, dir: File, timeoutSeconds: Int): String = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext "Give a command."
        val builder = ProcessBuilder("/system/bin/sh", "-c", command).directory(dir).redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", dir.path)
            put("PWD", dir.path)
            put("TMPDIR", context.cacheDir.path)
            put("PATH", "/system/bin:/system/xbin:/vendor/bin")
        }
        val process = builder.start()
        process.outputStream.close()
        val out = StringBuilder()
        try {
            val reader = launch {
                process.inputStream.bufferedReader().use { r ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = r.read(buffer)
                        if (n < 0) break
                        if (out.length < MAX_OUTPUT) out.append(buffer, 0, n)
                    }
                }
            }
            val exit = withTimeoutOrNull(timeoutSeconds * 1000L) { runInterruptible { process.waitFor() } }
            if (exit == null) {
                process.destroyForcibly()
                reader.join()
                out.append("\n[stopped: it took longer than $timeoutSeconds seconds]")
                return@withContext out.toString().take(MAX_OUTPUT)
            }
            reader.join()
            (if (out.isEmpty()) "(no output)" else out.toString().take(MAX_OUTPUT)) + (if (exit != 0) "\n[exit code $exit]" else "")
        } finally {
            process.destroyForcibly()
        }
    }

    private fun py(): com.chaquo.python.PyObject {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance().getModule("devx_runner")
    }

    private suspend fun python(code: String, dir: File, timeoutSeconds: Int): String = coroutineScope {
        val libs = File(dir, ".pylibs")
        val job = async(Dispatchers.IO) { py().callAttr("run", code, dir.path, timeoutSeconds, libs.path).toString() }
        try {
            job.await().take(MAX_OUTPUT).ifBlank { "(no output)" }
        } catch (e: CancellationException) {
            runCatching { py().callAttr("interrupt") }
            throw e
        }
    }

    private suspend fun pip(pkg: String, dir: File): String = coroutineScope {
        if (pkg.isBlank()) return@coroutineScope "Give the package name."
        val libs = File(dir, ".pylibs")
        val job = async(Dispatchers.IO) {
            runCatching { py().callAttr("pip_install", pkg.trim(), libs.path).toString() }.getOrElse { "Could not install $pkg: ${it.message?.lines()?.firstOrNull { l -> l.isNotBlank() } ?: it.javaClass.simpleName}" }
        }
        job.await().ifBlank { "$pkg is already installed." }
    }

    private companion object {
        const val MAX_OUTPUT = 20_000
        const val MAX_WRITE = 3_000_000
        const val MAX_READ_BYTES = 4_000_000
        const val MAX_ARTIFACT = 200L * 1024 * 1024
    }
}

internal fun strList(description: String): JSONObject =
    JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", description)
