package com.apvcode.devx.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** The assistant's way to make, list, change, run and delete scheduled tasks ("every morning send me the news about AI"). */
class TaskTools(private val context: Context) {
    val names = setOf("task_create", "task_list", "task_update", "task_delete", "task_run")

    fun definitions(): List<WireTool> = listOf(
        tool(
            "task_create",
            "Make a scheduled task: at a set time (daily or on chosen days) Devx asks the model the prompt by itself and saves the answer as a chat, with a notification. For things wanted regularly (\"every morning find AI news\"). Write the prompt as a complete request for a model with no other context, in the person's language, and say what the answer should look like.",
            schema(
                "title" to str("A short name for the task."),
                "prompt" to str("What the model is asked each time: a complete, self-contained request."),
                "time" to str("When, as HH:mm in the phone's local time (24-hour)."),
                "days" to strList("Days: mon, tue, wed, thu, fri, sat, sun, or \"daily\" (default daily)."),
                "model" to str("A model's name to use for it (optional; default the model the person has chosen in the app)."),
                required = listOf("title", "prompt", "time"),
            ),
        ),
        tool("task_list", "The scheduled tasks: number, name, time, days, model, and whether each is on.", schema()),
        tool(
            "task_update",
            "Change a scheduled task by its number: any of title, prompt, time, days, model, or turn it on or off.",
            schema(
                "id" to int("The task's number from task_list."),
                "title" to str("New name (optional)."),
                "prompt" to str("New prompt (optional)."),
                "time" to str("New time HH:mm (optional)."),
                "days" to strList("New days (optional)."),
                "model" to str("New model name, or \"default\" (optional)."),
                "enabled" to phoneBool("true to turn on, false to turn off (optional)."),
                required = listOf("id"),
            ),
        ),
        tool("task_delete", "Delete a scheduled task by its number (or its name).", schema("which" to str("The number or words from the name."))),
        tool("task_run", "Run a scheduled task right now (its answer comes as a chat and a notification).", schema("which" to str("The number or words from the name."))),
    )

    suspend fun run(name: String, args: JSONObject, report: ToolReport): ToolOutcome {
        fun act(detail: String) = report.activity(ToolActivity(ToolKind.Phone, detail))
        return when (name) {
            "task_create" -> create(args, ::act)
            "task_list" -> {
                act("")
                val list = Tasks.all(context)
                ToolOutcome(if (list.isEmpty()) "No tasks are set." else list.joinToString("\n") { describe(it) })
            }
            "task_update" -> update(args, ::act)
            "task_delete" -> {
                val task = find(args.optString("which")) ?: return ToolOutcome("No task matches \"${args.optString("which")}\".")
                act(task.title)
                Tasks.remove(context, task.id)
                ToolOutcome("Deleted task #${task.id} ${task.title}.")
            }
            "task_run" -> {
                val task = find(args.optString("which")) ?: return ToolOutcome("No task matches \"${args.optString("which")}\".")
                act(task.title)
                Tasks.runNow(context, task.id)
                ToolOutcome("Task #${task.id} ${task.title} started; its answer will come as a chat and a notification.")
            }
            else -> ToolOutcome("Unknown tool: $name")
        }
    }

    private fun find(which: String): ScheduledTask? {
        val key = which.trim().removePrefix("#")
        val list = Tasks.all(context)
        key.toIntOrNull()?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        return list.firstOrNull { it.title.contains(key, true) }
    }

    private fun describe(t: ScheduledTask): String {
        val next = if (t.enabled) Tasks.nextRun(t)?.let { " next " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16) } else null
        return "#${t.id} \"${t.title}\" at %02d:%02d ${Tasks.daysText(t.days)}, model ${t.model?.model ?: "default"}, ${if (t.enabled) "on" else "off"}${next ?: ""}".format()
    }

    /** A model's name (as the person says it) to the provider and model it belongs to. */
    private fun modelNamed(name: String): ModelRef? {
        val wanted = name.trim()
        if (wanted.isEmpty() || wanted.equals("default", true) || wanted.equals("current", true)) return null
        val providers = Store(context).loadProviders()
        providers.forEach { p -> p.models.firstOrNull { it.equals(wanted, true) }?.let { return ModelRef(p.id, it) } }
        providers.forEach { p -> p.models.firstOrNull { it.contains(wanted, true) }?.let { return ModelRef(p.id, it) } }
        return null
    }

    private fun clock(text: String): Pair<Int, Int>? {
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    private fun daysOf(args: JSONObject): Int? {
        val array = args.optJSONArray("days") ?: return null
        val names = (0 until array.length()).map { array.optString(it) }
        return if (names.isEmpty()) null else Tasks.daysFrom(names)
    }

    private fun create(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val title = args.optString("title").trim()
        val prompt = args.optString("prompt").trim()
        if (title.isEmpty() || prompt.isEmpty()) return ToolOutcome("Give a title and a prompt.")
        val (h, m) = clock(args.optString("time")) ?: return ToolOutcome("Write the time as HH:mm (24-hour).")
        val days = daysOf(args) ?: ScheduledTask.ALL
        if (days == 0) return ToolOutcome("Give days: mon, tue, wed, thu, fri, sat, sun or daily.")
        val wanted = args.optString("model")
        val model = modelNamed(wanted)
        act(title)
        val task = Tasks.add(context, title, prompt, h, m, days, model)
        val note = if (wanted.isNotBlank() && model == null && !wanted.equals("default", true) && !wanted.equals("current", true)) " (No model named \"$wanted\" was found, so the default one is used.)" else ""
        return ToolOutcome("Task created: ${describe(task)}.$note It runs by itself at that time and the answer comes as a chat with a notification.")
    }

    private fun update(args: JSONObject, act: (String) -> Unit): ToolOutcome {
        val task = Tasks.get(context, args.optInt("id", -1)) ?: return ToolOutcome("No task with that number.")
        act(task.title)
        args.optString("title").takeIf { it.isNotBlank() }?.let { task.title = it.trim() }
        args.optString("prompt").takeIf { it.isNotBlank() }?.let { task.prompt = it.trim() }
        args.optString("time").takeIf { it.isNotBlank() }?.let { text ->
            val (h, m) = clock(text) ?: return ToolOutcome("Write the time as HH:mm (24-hour).")
            task.hour = h; task.minute = m
        }
        daysOf(args)?.let { if (it != 0) task.days = it }
        if (args.has("model")) task.model = modelNamed(args.optString("model"))
        if (args.has("enabled")) task.enabled = args.optBoolean("enabled", true)
        Tasks.update(context, task)
        return ToolOutcome("Updated: ${describe(task)}.")
    }

    companion object {
        fun promptSection(): String = """
## Scheduled tasks
You can make tasks that Devx runs by itself at a set time (task_create, task_list, task_update, task_delete, task_run). When the person wants something done regularly ("every morning find AI news", "remind me with a summary on Fridays"), make a task for it instead of promising to do it later. When something would clearly be better as a task (a daily briefing, a weekly report), offer it in one short sentence. A task's prompt must stand on its own: the model that runs it sees nothing of this chat. Say the time and the days that were set.
""".trim()
    }
}

private fun phoneBool(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)
