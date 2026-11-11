package com.apvcode.devx.model

enum class Role { User, Assistant }

/** One piece of a message. An assistant message is a list of blocks. */
sealed interface Block {
    data class Text(val markdown: String) : Block

    data class Code(val language: String, val code: String) : Block

    /** An SVG the assistant drew right in the text. */
    data class Svg(val caption: String, val svg: String) : Block

    /** A file the assistant handed over: shown as a card; [id] is its id in the app's folder of such files. */
    data class File(val id: String) : Block

    /**
     * An interactive card the assistant composed itself, described as JSON (see GenerativeUi.kt).
     * It is not a template: the assistant decides which elements to use.
     */
    data class Ui(val json: String) : Block

    /** The agent on the computer asks for permission to do something risky. */
    data class Approval(val command: String) : Block

    /** A card or a picture whose description is still arriving. */
    data object Building : Block
}

data class ChatMessage(
    val id: Long,
    val role: Role,
    val blocks: List<Block>,
)

/**
 * Idle: nothing happening. Working: the agent runs on the computer. Replied: the assistant answered
 * and you have not looked yet. Waiting: the assistant asks you a question.
 */
enum class ChatStatus { Idle, Working, Replied, Waiting }

data class ChatSummary(
    val id: String,
    val title: String,
    /** null = a plain chat that belongs to no project. */
    val project: String?,
    val preview: String,
    val time: String,
    val status: ChatStatus,
    val pinned: Boolean = false,
    val locked: Boolean = false,
)

enum class TaskStatus { Running, Done, Failed }

data class BgTask(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val elapsed: String,
    val lastLine: String,
)

data class Computer(val name: String, val os: String, val online: Boolean)

data class McpServer(val name: String, val description: String, val active: Boolean)

data class ModelOption(val name: String, val hint: String)
