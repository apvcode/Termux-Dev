package com.apvcode.devx.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A project: a name, what it is for, what the assistant should always keep in mind ([instructions]), a look
 * (icon and colour), and a knowledge base of its own ([memory]: short notes the assistant writes, and an overview).
 * Chats keep the project by its name; the id only names the files of the knowledge base, so a rename loses nothing.
 */
class ProjectInfo(
    val id: String,
    description: String = "",
    instructions: String = "",
    icon: String = ProjectLook.DEFAULT_ICON,
    color: Long = ProjectLook.colors.first(),
    val memory: MemoryStore,
) {
    var description by mutableStateOf(description)
    var instructions by mutableStateOf(instructions)
    var icon by mutableStateOf(icon)
    var color by mutableLongStateOf(color)
}

/** The icons and colours a project can have. */
object ProjectLook {
    const val DEFAULT_ICON = "folder"

    /** Icon ids; each one has a drawable on the screen side. */
    val icons = listOf(
        "folder", "code", "terminal", "book", "school",
        "music", "palette", "briefcase", "chart", "flask",
        "heart", "plane", "tool", "paw", "bulb",
        "rocket", "gamepad", "cart", "dollar", "language",
        "news", "bolt", "star", "coffee", "brain",
    )

    /** Calm colours: the first is the plain white-grey of the app, the others are used sparingly. */
    val colors = listOf(0xFFE6E6E6L, 0xFFE5736AL, 0xFFE8A05CL, 0xFFD9C25AL, 0xFF6FBF8EL, 0xFF5FB3C4L, 0xFF6C9BE8L, 0xFFA48BE0L, 0xFFD981B5L)
}

/** Where the details of the projects are kept (one small file); the names of the projects are in projects.json. */
class ProjectStore(private val context: Context) {
    private val file = File(context.filesDir, "projects_meta.json")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "devx-projects").apply { isDaemon = true } }

    /** The details by project name; a project without any gets the plain defaults. */
    fun load(names: List<String>): Map<String, ProjectInfo> {
        val root = runCatching { JSONObject(file.takeIf { it.exists() }?.readText() ?: "{}") }.getOrDefault(JSONObject())
        return names.associateWith { name ->
            val o = root.optJSONObject(name)
            val id = o?.optString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(8)
            ProjectInfo(
                id = id,
                description = o?.optString("description").orEmpty(),
                instructions = o?.optString("instructions").orEmpty(),
                icon = o?.optString("icon")?.takeIf { it in ProjectLook.icons } ?: ProjectLook.DEFAULT_ICON,
                color = o?.optLong("color", ProjectLook.colors.first()) ?: ProjectLook.colors.first(),
                memory = memoryOf(id),
            )
        }
    }

    fun memoryOf(id: String) = MemoryStore(context, "project_$id.json", "project_${id}_summary.json")

    fun save(all: Map<String, ProjectInfo>) {
        val json = JSONObject().also { root ->
            all.forEach { (name, p) ->
                root.put(
                    name,
                    JSONObject().put("id", p.id).put("description", p.description).put("instructions", p.instructions)
                        .put("icon", p.icon).put("color", p.color),
                )
            }
        }.toString()
        writer.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }
}
