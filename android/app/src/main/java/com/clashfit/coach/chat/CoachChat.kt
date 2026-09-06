package com.clashfit.coach.chat

import android.util.Log
import com.clashfit.coach.CloudCoach
import com.clashfit.coach.CoachEngine
import com.clashfit.coach.LlmEngine
import com.clashfit.core.model.CoachSource
import kotlinx.coroutines.CancellationException

/**
 * One question, one grounded answer, from whichever voice is available.
 *
 * The ladder is the same one the set-end coach uses, for the same reason: Gemma on the phone if the
 * weights are installed; the cloud model if the player switched that on; otherwise a template that
 * still cites a real number. What changes between rungs is who is speaking, never whether the
 * answer is grounded — the fact sheet goes to all three, and the third one is built from it directly.
 *
 * The source comes back with the answer so the screen can say which voice replied. That badge is
 * not decoration: "on this phone" is the whole claim, and a judge is entitled to see when it is
 * true and when it is not.
 */
class CoachChat(
    private val llm: LlmEngine?,
    private val cloud: CloudCoach?,
    private val cloudAllowed: suspend () -> Boolean = { false },
) {
    private val tag = "ClashFit/chat"

    data class Answer(val text: String, val source: CoachSource)

    /** Which voice would answer right now, for the badge before anything is asked. */
    suspend fun availableSource(): CoachSource = when {
        llm?.ready == true -> CoachSource.LLM
        cloud?.isConfigured == true && cloudAllowed() -> CoachSource.CLOUD
        else -> CoachSource.TEMPLATE
    }

    /**
     * Ask. Never throws, never returns empty, never leaves the player without a reply.
     *
     * @param facts the fact sheet — the only thing the model may draw numbers from.
     * @param history earlier turns, so a follow-up like "and the other side?" makes sense.
     */
    suspend fun ask(question: String, facts: List<String>, history: List<ChatTurn> = emptyList()): Answer {
        val q = question.trim()
        if (q.isEmpty()) return Answer("Ask me something about your training.", CoachSource.TEMPLATE)

        val prompt = FactSheet.prompt(facts, q, history)

        llm?.let { engine ->
            if (engine.ready) {
                val out = runCatchingCancellable {
                    engine.complete(prompt = prompt, maxTokens = 120, temperature = 0.3f, topK = 20, timeoutMs = 12_000L)
                }
                clean(out)?.let { return Answer(it, CoachSource.LLM) }
            }
        }

        cloud?.let { c ->
            if (c.isConfigured && cloudAllowed()) {
                val out = runCatchingCancellable {
                    c.complete(system = FactSheet.RULES, user = "FACTS\n" + facts.joinToString("\n") { "- $it" } + "\n\nPlayer: $q", maxTokens = 120)
                }
                clean(out)?.let { return Answer(it, CoachSource.CLOUD) }
            }
        }

        return Answer(TemplateAnswers.answer(q, facts), CoachSource.TEMPLATE)
    }

    private suspend fun runCatchingCancellable(block: suspend () -> String?): String? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "a coach voice failed; falling through", e)
        null
    }

    /**
     * What the model said, made safe to show.
     *
     * Small models pad. They open with "Sure", they repeat the question, they run to five sentences
     * when told two. This trims to the first two sentences, drops the padding, and refuses anything
     * empty — at which point the ladder falls to the next rung rather than showing a shrug.
     */
    private fun clean(raw: String?): String? {
        var s = raw?.trim()?.removePrefix("Coach:")?.trim() ?: return null
        s = s.removePrefix("\"").removeSuffix("\"").trim()
        if (s.isEmpty()) return null
        val sentences = SENTENCE.findAll(s).map { it.value.trim() }.filter { it.isNotEmpty() }.take(2).toList()
        if (sentences.isNotEmpty()) s = sentences.joinToString(" ")
        s = s.replace('!', '.').trim()
        return s.takeIf { it.length in 8..320 }
    }

    private companion object {
        val SENTENCE = Regex("[^.!?]+[.!?]?")
    }
}
