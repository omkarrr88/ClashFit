package com.clashfit.coach.chat

import com.clashfit.core.model.SetTelemetry
import com.clashfit.data.SessionEntity
import com.clashfit.data.StreakEntity
import com.clashfit.meta.MetaState
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Everything the coach is allowed to know, as plain lines of text.
 *
 * A chat that answers from nothing is a party trick: it will invent a depth, congratulate you on a
 * streak you do not have, and be confidently wrong in front of a judge. This is the antidote. The
 * model is handed a short, literal list of measurements taken from this player's own database, and
 * told to answer only from it.
 *
 * Short on purpose. Gemma 3n E2B is a two-billion-parameter model with a small context, and the
 * whole prompt — rules, sheet, history, question — has to fit with room for an answer. Every line
 * here earns its place: the numbers a player actually asks about, and nothing else.
 *
 * There is no free text in a fact sheet. Nothing a player typed, no display name, no email. What
 * goes in is numbers and the words this app already prints on its own screens.
 */
object FactSheet {

    /** The set just finished, when the chat is opened from a fight. */
    fun forSet(t: SetTelemetry): List<String> = buildList {
        add("exercise: ${t.exercise}")
        add("set number: ${t.sessionSetIndex}")
        add("reps this set: ${t.reps}")
        add("form average: ${t.formMeanPct} out of 100")
        add("form first three reps: ${t.formFirst3Pct}")
        add("form last three reps: ${t.formLast3Pct}")
        t.depthCm?.let { add("depth: $it cm") }
        t.depthDropCm?.let { if (it != 0) add("depth lost across the set: $it cm") }
        add("speed lost across the set: ${t.velocityLossPct} percent")
        add("range lost across the set: ${t.romLossPct} percent")
        add("fatigue at the end: ${t.fatigueBand.name.lowercase()}")
        add("trend: ${t.trend.name.lowercase()}")
        t.bestRep?.let { add("best rep: number ${it.index} at ${(it.form * 100).roundToInt()} out of 100") }
        t.worstRep?.let { r ->
            add("worst rep: number ${r.index} at ${(r.form * 100).roundToInt()} out of 100" +
                (r.reason?.let { ", weakest part was $it" } ?: ""))
        }
        add("longest clean streak: ${t.comboReps} reps, multiplier ${"%.1f".format(t.comboMax)}")
        add("boss health left: ${t.bossHpPct} percent")
        t.asymmetryPct?.let { pct ->
            val side = t.weakerSide?.lowercase()
            add("left-right difference: $pct percent" + (side?.let { ", weaker side $it" } ?: ""))
        }
    }

    /**
     * The player's recent history, when the chat is opened from the profile rather than a fight.
     *
     * Ten sessions, not fifty: a small model given a long table starts averaging things nobody
     * asked about. Ten is enough to answer "am I getting better" honestly.
     */
    fun forHistory(
        sessions: List<SessionEntity>,
        streak: StreakEntity?,
        meta: MetaState?,
        nowMs: Long,
    ): List<String> = buildList {
        val done = sessions.filter { it.endedAtMs != null }
        if (done.isEmpty()) {
            add("this player has not finished a session yet")
            return@buildList
        }
        add("sessions finished: ${done.size}")
        add("most recent first, up to ten:")
        done.take(10).forEach { s ->
            val days = TimeUnit.MILLISECONDS.toDays(nowMs - s.startedAtMs)
            val whenSaid = when (days) {
                0L -> "today"
                1L -> "yesterday"
                else -> "$days days ago"
            }
            add("- $whenSaid: ${s.exerciseId}, ${s.totalReps} reps, form score ${(s.formMean * 100).roundToInt()} percent, peak fatigue ${s.peakBand.lowercase()}")
        }
        val recentForm = done.take(5).map { it.formMean }
        val olderForm = done.drop(5).take(5).map { it.formMean }
        if (recentForm.isNotEmpty() && olderForm.isNotEmpty()) {
            val r = (recentForm.average() * 100).roundToInt()
            val o = (olderForm.average() * 100).roundToInt()
            // Both averages, and the word "average" on both, because a lone number next to the
            // phrase "last five sessions" reads to a small model as a change over those sessions —
            // it turned an average form score of 20 into "a 20 percent increase".
            add("average form score over the last five sessions: $r percent; over the five before that: $o percent")
        }
        streak?.let {
            // Pluralised, because this line is spliced into the chat's opening sentence as well
            // as handed to the model, and "1 days" is the kind of thing a judge reads out loud.
            fun days(n: Int) = if (n == 1) "1 day" else "$n days"
            add("current streak: ${days(it.current)}, best ever: ${days(it.best)}")
        }
        meta?.let {
            add("level ${it.progress.level}, called ${it.progress.title}")
            add("weekly challenge ${it.weekly.challenge.title}: ${it.weekly.value} of ${it.weekly.challenge.target}")
        }
    }

    /**
     * The rules, verbatim, every turn.
     *
     * A 2B model does not remember a system prompt from six messages ago the way a frontier model
     * does, so these ride along with each question. "Say you do not know" is the most important
     * line in the app: a coach that guesses a number is worse than one that shrugs, because the
     * whole claim of this project is that everything on screen was measured.
     *
     * The two worked examples are doing most of the work. Told to "speak like a coach" a small
     * model produces "You are completing 7 sessions. Your current form is 20." — every number
     * correct, nothing said. Shown one answer in the register wanted, it copies the shape: name
     * the measurement, say what it means, give one thing to do. The second example exists so that
     * admitting ignorance has a demonstrated shape too, rather than being a rule it can drift past.
     */
    const val RULES = """You are a strength coach. You have watched every rep this player has done, because the phone measured them. You are talking to them between sets.

You will be given FACTS: measurements from this player's own training.

How to answer:
- Answer only from the FACTS. Never state a number that is not in them.
- If the FACTS do not answer the question, say so plainly and say what you would need.
- Name one number, say what it means, then give one thing to do about it.
- Keep the unit on the number: 78 percent, 46 cm, 12 reps.
- A score is not a change. Never call a number an increase, a decrease, an improvement or a drop unless the FACTS give you both the before and the after.
- Never say you lack data and then quote a number in the same answer. Do one or the other.
- Two sentences. Speak the way a coach speaks in a gym, not the way a report reads.
- Never comment on their body, weight, appearance or fitness level.
- Never apologise, never use exclamation marks, never use emoji.

EXAMPLE
FACTS
- reps this set: 12
- form average 78 percent, first three 88 percent, last three 64 percent
- depth: 46 cm, lost 7 cm by the end
Player: why did my depth drop?
Coach: You lost 7 cm of depth between your first three reps and your last three, so that is fatigue rather than technique. Stop the set two reps earlier and the last ones will look like the first.

EXAMPLE
FACTS
- this player has not finished a session yet
Player: am I getting better?
Coach: I have nothing measured yet, so anything I said would be a guess. Finish one set and I can tell you exactly what changed."""

    /** Rules, facts and the question, in the order a small model reads best. */
    fun prompt(facts: List<String>, question: String, history: List<ChatTurn> = emptyList()): String =
        buildString {
            appendLine(RULES)
            appendLine()
            appendLine("FACTS")
            facts.forEach { appendLine("- $it") }
            if (history.isNotEmpty()) {
                appendLine()
                appendLine("EARLIER IN THIS CONVERSATION")
                // Only the last few turns: the sheet matters more than the chat, and context is short.
                history.takeLast(4).forEach { turn ->
                    appendLine("${if (turn.fromPlayer) "Player" else "Coach"}: ${turn.text}")
                }
            }
            appendLine()
            appendLine("Player: $question")
            append("Coach:")
        }
}

/** One line of the conversation. [fromPlayer] false means the coach said it. */
data class ChatTurn(
    val text: String,
    val fromPlayer: Boolean,
    val source: com.clashfit.core.model.CoachSource? = null,
)
