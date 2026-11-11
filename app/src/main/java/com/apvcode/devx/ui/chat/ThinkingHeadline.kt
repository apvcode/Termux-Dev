package com.apvcode.devx.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.apvcode.devx.data.Msg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * A few words about what the model is thinking about right now. Reasoning summaries (Claude, Gemini,
 * OpenAI) come in paragraphs that start with a bold title, so the latest title is the headline.
 * Raw thinking (DeepSeek, GLM and the like) has no titles: then the first words of the latest
 * sentence that says something stand in, without the usual "Okay, let me" start.
 *
 * Only the tail is looked at, so that a very long reasoning does not cost more with every token.
 */
fun thinkingHeadline(reasoning: String): String? {
    val tail = if (reasoning.length > TAIL) reasoning.takeLast(TAIL) else reasoning

    val titles = TITLE.findAll(tail).toList()
    titles.lastOrNull()?.let { return it.groupValues[1].trim().trimEnd(':', '.', '…') }

    val paragraphs = tail.trim().split(Regex("\\n\\s*\\n")).filter { it.isNotBlank() }
    for (paragraph in paragraphs.asReversed()) {
        val plain = paragraph.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim()
        val sentences = SENTENCE.findAll(plain).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        // The newest sentence that has something to say: "Wait." or "Hmm, OK." say nothing.
        for (sentence in sentences.asReversed()) {
            val words = stripFiller(sentence).split(' ').filter { it.isNotBlank() }
            if (words.size >= MIN_WORDS) {
                val head = words.take(MAX_WORDS).joinToString(" ")
                return head.replaceFirstChar { it.uppercase() } + if (words.size > MAX_WORDS) "…" else ""
            }
        }
    }
    return null
}

private const val TAIL = 3000
private const val MIN_WORDS = 3
private const val MAX_WORDS = 5
private val TITLE = Regex("\\*\\*([^*\\n]{3,70})\\*\\*")
private val SENTENCE = Regex("[^.!?\\n]+")

// "Okay, let me" and the like may be stacked: strip until nothing more comes off.
private val FILLER = Regex(
    "^(okay|ok|alright|so|hmm+|well|now|first|then|wait|actually|let me|let's|i need to|i should|i'll|i will|" +
        "the user (wants|is asking|asks) (me )?(to )?|" +
        "хорошо|итак|ладно|так|значит|теперь|сначала|потом|давай(те)?|посмотрим|подожди|нужно|надо|мне нужно|" +
        "пользователь (хочет|просит|спрашивает)( чтобы)?)[,:]?\\s+",
    RegexOption.IGNORE_CASE,
)

private fun stripFiller(sentence: String): String {
    var stripped = sentence
    while (true) {
        val next = stripped.replace(FILLER, "")
        if (next == stripped) return stripped
        stripped = next
    }
}

/**
 * The headline as the screen shows it: it changes at most every few seconds. Models that think out
 * loud change what they are talking about many times a second; shown as it is, the label would
 * flicker and tear. The newest headline always waits its turn, and the one before it is never shown.
 */
@Composable
fun rememberThinkingHeadline(msg: Msg): String? {
    var shown by remember(msg.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(msg.id) {
        snapshotFlow { thinkingHeadline(msg.reasoning) }
            .filterNotNull()
            .distinctUntilChanged()
            .conflate()
            .collect { headline ->
                shown = headline
                delay(MIN_SHOWN_MS)
            }
    }
    return shown
}

private const val MIN_SHOWN_MS = 2500L
