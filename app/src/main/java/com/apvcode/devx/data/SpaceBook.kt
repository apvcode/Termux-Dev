package com.apvcode.devx.data

/**
 * The list of notebooks and projects with their details, and the chats that sit in them, as plain data: making, renaming, copying,
 * taking out and putting back. It knows nothing about the screen, the disk or the model, so every rule here (a name that is taken is
 * refused, the chats follow a rename, a chat whose project is gone is not left in no list) is tested on its own. The screen's model
 * calls it and then writes what it says has changed.
 */
class SpaceBook(
    private val names: MutableList<String>,
    private val infos: MutableMap<String, ProjectInfo>,
    private val chats: MutableList<Chat>,
) {
    /** Makes a new one. False when the name cannot be used (empty, taken, reserved). [make] builds the details from the new short id. */
    fun create(name: String, id: String, make: (String) -> ProjectInfo): Boolean {
        val trimmed = name.trim()
        if (SpaceRules.problem(trimmed, null, names) != null) return false
        names += trimmed
        infos[trimmed] = make(id)
        return true
    }

    /**
     * Gives [old] the name [new]: the chats inside it follow, and so do the messages that pointed at it with @. Gives the chats that
     * changed (to be written), or null when nothing was done (the name is empty, the same, or taken: that one would be overwritten).
     */
    fun rename(old: String, new: String): List<Chat>? {
        val name = new.trim()
        if (SpaceRules.problem(name, old, names) != null || old !in names) return null
        val index = names.indexOf(old)
        if (index >= 0) names[index] = name
        infos.remove(old)?.let { infos[name] = it }
        val touched = LinkedHashSet<Chat>()
        chats.filter { it.project == old }.forEach { it.project = name; touched += it }
        chats.forEach { c ->
            var changed = false
            c.messages.forEach { m -> if (old in m.mentions) { m.mentions = m.mentions.map { if (it == old) name else it }; changed = true } }
            if (changed) touched += c
        }
        return touched.toList()
    }

    /**
     * A copy to start again: the description, instructions, goal, look, the files and sites, the lessons (not done) and the cards (new).
     * Not copied: the chats, the memory notes, the days of study, the dates. [copyName] is made free ("name 2"). Gives the name of the copy.
     */
    fun duplicate(name: String, copyName: String, id: String, make: (id: String, plan: StudyPlan) -> ProjectInfo): String? {
        val info = infos[name] ?: return null
        val copy = SpaceRules.uniqueName(copyName) { it in names }
        val made = make(id, info.plan.template())
        made.description = info.description
        made.instructions = info.instructions
        made.icon = info.icon
        made.color = info.color
        made.kind = info.kind
        made.goal = info.goal
        made.useNotes = info.useNotes
        made.sources.addAll(info.sources)
        names += copy
        infos[copy] = made
        return copy
    }

    /** What was taken out: the details and the chats that were inside. */
    class Taken(val info: ProjectInfo, val chats: List<Chat>)

    /** Takes a project out of the list, with its chats. The caller keeps them somewhere (the trash) before it lets go. Null if there is none by that name. */
    fun take(name: String): Taken? {
        val info = infos[name] ?: return null
        val inside = chats.filter { it.project == name }
        chats.removeAll(inside.toSet())
        names.remove(name)
        infos.remove(name)
        return Taken(info, inside)
    }

    /**
     * Puts a project back with the chats it had. A name that is taken gets a number; a chat that is already in the list (the same id) is
     * left as it is. Gives the name it has now.
     */
    fun restore(name: String, info: ProjectInfo, inside: List<Chat>): String {
        val now = SpaceRules.uniqueName(name) { it in names }
        names += now
        infos[now] = info
        inside.forEach { chat ->
            if (chats.any { it.id == chat.id }) return@forEach
            chat.project = now
            chats += chat
        }
        return now
    }

    /** Puts a chat back. False when it is already there; a chat whose project is gone goes to the plain list. */
    fun restore(chat: Chat): Boolean {
        if (chats.any { it.id == chat.id }) return false
        if (chat.project != null && chat.project !in names) chat.project = null
        chats += chat
        return true
    }

    /**
     * A chat that points at a project that is not in the list (the list or the details were damaged once) would be in no section of the
     * side panel at all: the project is made again with plain defaults. Gives how many were made.
     */
    fun adoptOrphans(id: () -> String, make: (String) -> ProjectInfo): Int {
        val lost = chats.mapNotNull { it.project }.distinct().filter { it !in names }
        lost.forEach { name ->
            names += name
            infos[name] = make(id())
        }
        return lost.size
    }

    /**
     * A rename cut short (the app was killed after the project had its new name but before every chat was written) leaves chats under
     * the old name; each chat also knows its project's id. Gives the chats that were moved to the present name (to be written).
     */
    fun healRenamed(): List<Chat> {
        val byId = infos.entries.associate { it.value.id to it.key }
        return chats.filter { chat ->
            val now = SpaceRules.healedProject(chat.project, chat.projectId, byId) ?: return@filter false
            chat.project = now
            true
        }
    }

    companion object {
        private val FILE_BLOCK = Regex("```devx-file\\n([^\\n`]+)\\n```")

        /** The ids of the files a chat leaves on disk besides itself (the attachments, and what the assistant made): kept while it is in the trash. */
        fun filesOf(chat: Chat): Set<String> =
            chat.messages.flatMap { m -> m.attachments.map { it.id } + FILE_BLOCK.findAll(m.text).map { it.groupValues[1] } }.toSet()

        /** The ids of the files the assistant made inside a chat's messages. */
        fun madeFiles(chat: Chat): List<String> = chat.messages.flatMap { m -> FILE_BLOCK.findAll(m.text).map { it.groupValues[1] }.toList() }
    }
}
