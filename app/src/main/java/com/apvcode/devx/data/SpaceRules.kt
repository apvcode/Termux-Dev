package com.apvcode.devx.data

/**
 * The rules about the names of notebooks and projects and about the links from chats to them, kept apart from the screen's model so
 * that they can be tested on their own (this is where the data losses were: a rename into a taken name, a rename cut short).
 */
object SpaceRules {
    /** Why a name cannot be used; null when it can. */
    enum class Problem { Empty, Same, Taken, Reserved }

    /** [own]: the present name when renaming (null when making a new one). [existing]: every name in use. */
    fun problem(name: String, own: String?, existing: Collection<String>): Problem? = when {
        name.isBlank() -> Problem.Empty
        name == own -> Problem.Same
        name == ProjectStore.VERSION_KEY -> Problem.Reserved
        name in existing -> Problem.Taken
        else -> null
    }

    /** [base] if it is free, else "base 2", "base 3"... */
    fun uniqueName(base: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(base)) return base
        var n = 2
        while (isTaken("$base $n")) n++
        return "$base $n"
    }

    /**
     * The name a chat should be under: the chat knows the id of its project ([projectId]), and the project may have been renamed since
     * the chat was written. Gives the project's present name when that differs from [current], else null (nothing to change).
     */
    fun healedProject(current: String?, projectId: String?, nameById: Map<String, String>): String? {
        val now = projectId?.let { nameById[it] } ?: return null
        return now.takeIf { it != current }
    }

    /**
     * [name] if no file of that name is [taken] (letters of any case count as the same), else "base (2).ext", "base (3).ext"...: the same
     * file added twice, or two files that share a name, must not hide each other.
     */
    fun numberedName(name: String, taken: Collection<String>): String {
        val used = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in used) return name
        // A name that only starts with a dot (".env") has no extension: the number goes after all of it.
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0 && dot < name.length - 1) name.substring(dot) else ""
        var n = 2
        while ("$base ($n)$ext".lowercase() in used) n++
        return "$base ($n)$ext"
    }
}
