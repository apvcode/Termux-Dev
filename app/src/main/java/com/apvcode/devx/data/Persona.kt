package com.apvcode.devx.data

import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.apvcode.devx.R

/**
 * How the assistant speaks: a style, thirteen fine settings in three groups, a name of its own
 * and their own instructions. Every setting has three levels and
 * the middle one is the assistant's own manner (it adds nothing to the prompt).
 */
object Persona {
    enum class Group(@StringRes val title: Int, @DrawableRes val icon: Int) {
        Answers(R.string.persona_group_answers, R.drawable.ic_c_message_dots),
        Tone(R.string.persona_group_tone, R.drawable.ic_c_mood_heart),
        Manner(R.string.persona_group_manner, R.drawable.ic_c_compass),
    }

    class Trait(
        val key: String,
        val group: Group,
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        /** Short labels of the three levels, for the switch. */
        @StringRes val levels: List<Int>,
        /** What each level means, in a sentence, under the switch. */
        @StringRes val hints: List<Int>,
        /** What the model is told for each level; the middle one is usually null. */
        val prompt: List<String?>,
    )

    private fun trait(key: String, group: Group, icon: Int, title: Int, l: List<Int>, h: List<Int>, low: String?, high: String?) =
        Trait(key, group, icon, title, l, h, listOf(low, null, high))

    val traits: List<Trait> = listOf(
        trait("length", Group.Answers, R.drawable.ic_c_align_left, R.string.persona_length,
            listOf(R.string.persona_length_0, R.string.persona_length_1, R.string.persona_length_2),
            listOf(R.string.persona_length_h0, R.string.persona_length_h1, R.string.persona_length_h2),
            "Keep answers short: the answer and the one or two things that matter, no preamble, no recap.",
            "Give detailed, thorough answers: the reasoning, the cases and the details that matter. Long answers are welcome when the subject needs them."),
        trait("approach", Group.Answers, R.drawable.ic_p_bulb, R.string.persona_approach,
            listOf(R.string.persona_approach_0, R.string.persona_approach_1, R.string.persona_approach_2),
            listOf(R.string.persona_approach_h0, R.string.persona_approach_h1, R.string.persona_approach_h2),
            "Give just the answer, without explaining how you got there, unless asked.",
            "Teach: explain step by step why it is so, build the idea up from the basics, and make sure the person can follow."),
        trait("expertise", Group.Answers, R.drawable.ic_c_stairs_up, R.string.persona_expertise,
            listOf(R.string.persona_expertise_0, R.string.persona_expertise_1, R.string.persona_expertise_2),
            listOf(R.string.persona_expertise_h0, R.string.persona_expertise_h1, R.string.persona_expertise_h2),
            "Explain in plain words, as to a beginner: no jargon, and define any term you must use.",
            "Speak to an expert: use precise technical terms freely and skip the basics."),
        trait("examples", Group.Answers, R.drawable.ic_c_list_details, R.string.persona_examples,
            listOf(R.string.persona_examples_0, R.string.persona_examples_1, R.string.persona_examples_2),
            listOf(R.string.persona_examples_h0, R.string.persona_examples_h1, R.string.persona_examples_h2),
            "Do not add examples unless asked.",
            "Illustrate your points with concrete examples, and for code with a short working snippet."),
        trait("structure", Group.Answers, R.drawable.ic_c_list, R.string.persona_structure,
            listOf(R.string.persona_structure_0, R.string.persona_structure_1, R.string.persona_structure_2),
            listOf(R.string.persona_structure_h0, R.string.persona_structure_h1, R.string.persona_structure_h2),
            "Write in plain flowing prose: no headings, no bullet lists and no tables unless the person asks or the content really needs one.",
            "Organise answers: headings, bullet lists, tables and bold key terms make them easy to scan."),
        trait("warmth", Group.Tone, R.drawable.ic_p_heart, R.string.persona_warmth,
            listOf(R.string.persona_warmth_0, R.string.persona_warmth_1, R.string.persona_warmth_2),
            listOf(R.string.persona_warmth_h0, R.string.persona_warmth_h1, R.string.persona_warmth_h2),
            "Be cool and matter-of-fact: no small talk, no sympathy phrases.",
            "Be warm and friendly, caring and encouraging, like a good friend."),
        trait("enthusiasm", Group.Tone, R.drawable.ic_c_flame, R.string.persona_enthusiasm,
            listOf(R.string.persona_enthusiasm_0, R.string.persona_enthusiasm_1, R.string.persona_enthusiasm_2),
            listOf(R.string.persona_enthusiasm_h0, R.string.persona_enthusiasm_h1, R.string.persona_enthusiasm_h2),
            "Stay level and reserved: no exclamation marks, no excitement.",
            "Be lively and enthusiastic about the person's ideas and work."),
        trait("formality", Group.Tone, R.drawable.ic_c_tie, R.string.persona_formality,
            listOf(R.string.persona_formality_0, R.string.persona_formality_1, R.string.persona_formality_2),
            listOf(R.string.persona_formality_h0, R.string.persona_formality_h1, R.string.persona_formality_h2),
            "Write casually, like a friend would, in simple everyday words (in languages with a formal and an informal \"you\", use the informal one).",
            "Write formally and professionally (in languages with a formal and an informal \"you\", use the formal one)."),
        trait("humor", Group.Tone, R.drawable.ic_c_mood_smile, R.string.persona_humor,
            listOf(R.string.persona_humor_0, R.string.persona_humor_1, R.string.persona_humor_2),
            listOf(R.string.persona_humor_h0, R.string.persona_humor_h1, R.string.persona_humor_h2),
            "Stay serious: no jokes.",
            "Be witty and playful: light humour and the odd joke are welcome when they fit."),
        trait("emoji", Group.Tone, R.drawable.ic_c_mood_happy, R.string.persona_emoji,
            listOf(R.string.persona_emoji_0, R.string.persona_emoji_1, R.string.persona_emoji_2),
            listOf(R.string.persona_emoji_h0, R.string.persona_emoji_h1, R.string.persona_emoji_h2),
            "Never use emoji.",
            "Use emoji often, to make the answer lively."),
        trait("directness", Group.Manner, R.drawable.ic_c_target, R.string.persona_directness,
            listOf(R.string.persona_directness_0, R.string.persona_directness_1, R.string.persona_directness_2),
            listOf(R.string.persona_directness_h0, R.string.persona_directness_h1, R.string.persona_directness_h2),
            "Be gentle: soften criticism and point out what is good first.",
            "Be blunt and honest: say plainly when the person is wrong or the idea is weak, push back, give no flattery and do not praise the question."),
        trait("opinions", Group.Manner, R.drawable.ic_c_speakerphone, R.string.persona_opinions,
            listOf(R.string.persona_opinions_0, R.string.persona_opinions_1, R.string.persona_opinions_2),
            listOf(R.string.persona_opinions_h0, R.string.persona_opinions_h1, R.string.persona_opinions_h2),
            "Stay neutral: lay out the options and their trade-offs without choosing for the person.",
            "Have opinions: when there are options, say which one you would choose and why."),
        trait("questions", Group.Manner, R.drawable.ic_c_help_circle, R.string.persona_questions,
            listOf(R.string.persona_questions_0, R.string.persona_questions_1, R.string.persona_questions_2),
            listOf(R.string.persona_questions_h0, R.string.persona_questions_h1, R.string.persona_questions_h2),
            "Do not ask clarifying questions: make a sensible assumption, say it in a few words and carry on.",
            "When a request is ambiguous or big, ask a short clarifying question first, before doing the work."),
    )

    fun indexOf(key: String) = traits.indexOfFirst { it.key == key }

    /** A ready combination: the settings it moves away from the middle; the rest stay in the middle. */
    class Style(val id: String, @StringRes val title: Int, @StringRes val hint: Int, @DrawableRes val icon: Int, set: Map<String, Int>) {
        val levels: List<Int> = traits.map { set[it.key] ?: 1 }
    }

    val styles: List<Style> = listOf(
        Style("default", R.string.persona_style_default, R.string.persona_style_default_hint, R.drawable.ic_c_circle_dot, emptyMap()),
        Style("concise", R.string.persona_style_concise, R.string.persona_style_concise_hint, R.drawable.ic_c_scissors,
            mapOf("length" to 0, "approach" to 0, "examples" to 0, "warmth" to 0, "enthusiasm" to 0, "humor" to 0, "emoji" to 0, "questions" to 0)),
        Style("detailed", R.string.persona_style_detailed, R.string.persona_style_detailed_hint, R.drawable.ic_c_file_description,
            mapOf("length" to 2, "approach" to 2, "examples" to 2, "structure" to 2)),
        Style("friendly", R.string.persona_style_friendly, R.string.persona_style_friendly_hint, R.drawable.ic_c_heart_handshake,
            mapOf("warmth" to 2, "enthusiasm" to 2, "formality" to 0, "directness" to 0)),
        Style("pro", R.string.persona_style_pro, R.string.persona_style_pro_hint, R.drawable.ic_p_briefcase,
            mapOf("formality" to 2, "warmth" to 0, "enthusiasm" to 0, "humor" to 0, "emoji" to 0, "structure" to 2)),
        Style("blunt", R.string.persona_style_blunt, R.string.persona_style_blunt_hint, R.drawable.ic_c_target_arrow,
            mapOf("length" to 0, "warmth" to 0, "enthusiasm" to 0, "directness" to 2, "opinions" to 2, "emoji" to 0, "questions" to 0)),
        Style("mentor", R.string.persona_style_mentor, R.string.persona_style_mentor_hint, R.drawable.ic_p_school,
            mapOf("length" to 2, "approach" to 2, "expertise" to 0, "examples" to 2, "warmth" to 2, "questions" to 2)),
        Style("expert", R.string.persona_style_expert, R.string.persona_style_expert_hint, R.drawable.ic_p_flask,
            mapOf("expertise" to 2, "examples" to 2, "structure" to 2, "opinions" to 2, "humor" to 0, "emoji" to 0)),
        Style("playful", R.string.persona_style_playful, R.string.persona_style_playful_hint, R.drawable.ic_c_confetti,
            mapOf("warmth" to 2, "enthusiasm" to 2, "formality" to 0, "humor" to 2, "emoji" to 2)),
    )

    private var levels by mutableStateOf(List(traits.size) { 1 })

    /** What the assistant is called, empty for its own name. */
    var name by mutableStateOf("")
        private set

    var instructions by mutableStateOf("")
        private set

    private var store: SharedPreferences? = null

    fun load(p: SharedPreferences) {
        store = p
        levels = traits.map { p.getInt("persona_" + it.key, 1).coerceIn(0, 2) }
        name = p.getString("persona_name", "").orEmpty()
        instructions = p.getString("persona_instructions", "").orEmpty()
    }

    fun level(index: Int): Int = levels[index]
    fun level(key: String): Int = levels[indexOf(key)]

    fun setLevel(index: Int, value: Int) {
        levels = levels.toMutableList().also { it[index] = value }
        store?.edit()?.putInt("persona_" + traits[index].key, value)?.apply()
    }

    fun apply(style: Style) {
        levels = style.levels
        val e = store?.edit()
        traits.forEachIndexed { i, t -> e?.putInt("persona_" + t.key, style.levels[i]) }
        e?.apply()
    }

    fun saveName(text: String) {
        name = text.take(MAX_NAME)
        store?.edit()?.putString("persona_name", name)?.apply()
    }

    fun saveInstructions(text: String) {
        instructions = text.take(MAX_INSTRUCTIONS)
        store?.edit()?.putString("persona_instructions", instructions)?.apply()
    }

    /** The style the settings add up to, or null when they match none (a custom mix). */
    fun current(): Style? = styles.firstOrNull { it.levels == levels }

    /** How many settings differ from the middle. */
    val changed: Int get() = levels.count { it != 1 }

    /** True when nothing differs from the assistant's own manner. */
    val isDefault get() = levels.all { it == 1 } && name.isBlank() && instructions.isBlank()

    fun reset() {
        apply(styles.first())
        saveName("")
        saveInstructions("")
    }

    /** The part of the system prompt, or null when the person left everything as it is. */
    fun section(): String? {
        val lines = traits.mapIndexedNotNull { i, t -> t.prompt[levels[i]] }
        val own = instructions.trim()
        val called = name.trim()
        if (lines.isEmpty() && own.isEmpty() && called.isEmpty()) return null
        return buildString {
            append("## How to speak\n")
            if (called.isNotEmpty()) append("In this app the person calls you \"").append(called).append("\". That is your name here: use it when you introduce yourself or are asked who you are (you are still the Devx assistant).\n")
            if (lines.isNotEmpty()) {
                append("The person set how they want you to write. These settings come before your defaults and before any instruction above about style (but never before safety or accuracy), and they hold for the whole conversation.\n")
                lines.forEach { append("- ").append(it).append('\n') }
            }
            if (own.isNotEmpty()) append("\nThe person's own instructions (follow them):\n").append(own).append('\n')
        }.trimEnd()
    }

    const val MAX_NAME = 30
    const val MAX_INSTRUCTIONS = 1500
}
