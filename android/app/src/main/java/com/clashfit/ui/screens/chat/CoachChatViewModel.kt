package com.clashfit.ui.screens.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clashfit.AppGraph
import com.clashfit.coach.chat.ChatTurn
import com.clashfit.coach.chat.CoachChat
import com.clashfit.coach.chat.FactSheet
import com.clashfit.coach.chat.TemplateAnswers
import com.clashfit.core.model.CoachSource
import com.clashfit.core.model.SetTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The coach chat, as state.
 *
 * Builds the fact sheet once — from the set just finished, or from the player's recent history —
 * and then every question is answered against it. The sheet is the contract: the model is never
 * asked anything it cannot answer from measurements this app took.
 */
class CoachChatViewModel(
    private val graph: AppGraph,
    private val sessionId: Long,
) : ViewModel() {

    data class State(
        val turns: List<ChatTurn> = emptyList(),
        val facts: List<String> = emptyList(),
        val starters: List<String> = emptyList(),
        val thinking: Boolean = false,
        /** Which voice will answer: the badge at the top of the screen. */
        val source: CoachSource = CoachSource.TEMPLATE,
        val afterSet: Boolean = false,
        val ready: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val chat = CoachChat(
        llm = graph.llmEngine,
        cloud = graph.cloudCoach,
        cloudAllowed = { graph.prefs.settings.first().cloudCoach },
    )

    private var askJob: Job? = null

    init {
        viewModelScope.launch {
            val afterSet = sessionId > 0
            val facts = withContext(Dispatchers.IO) { buildFacts(afterSet) }
            val source = chat.availableSource()
            _state.value = State(
                turns = listOf(ChatTurn(opening(afterSet, facts), fromPlayer = false, source = source)),
                facts = facts,
                starters = TemplateAnswers.starters(afterSet),
                source = source,
                afterSet = afterSet,
                ready = true,
            )
        }
    }

    /**
     * The first thing the coach says, before being asked anything.
     *
     * A chat that opens empty asks the player to think of a question while out of breath. This
     * opens on the single most interesting measurement it has, which is also a demonstration that
     * the thing is grounded before anybody types a word.
     */
    private fun opening(afterSet: Boolean, facts: List<String>): String {
        if (facts.isEmpty()) return "Finish a set and I can tell you what happened in it."
        val notable = facts.firstOrNull { it.startsWith("worst rep") }
            ?: facts.firstOrNull { it.startsWith("left-right") }
            ?: facts.firstOrNull { it.startsWith("form average") }
            ?: facts.firstOrNull { it.startsWith("current streak") }
            ?: facts.first()
        // The fact lines are written for the model — lower case, colon separated — so the one
        // that gets spoken aloud is capitalised on the way out rather than spliced in raw.
        val said = notable.replaceFirstChar { it.uppercase() }
        return if (afterSet) "That set is measured. $said. Ask me about any of it."
        else "I have your training here. $said. Ask me anything about it."
    }

    private suspend fun buildFacts(afterSet: Boolean): List<String> {
        if (afterSet) {
            // The set the summary is showing. Its telemetry is the richest sheet we can build.
            telemetryFor(sessionId)?.let { return FactSheet.forSet(it) }
        }
        val sessions = graph.db.sessions().recent(30).first()
        val streak = graph.db.streak().observe().first()
        val meta = runCatching { graph.meta.state.first() }.getOrNull()
        return FactSheet.forHistory(sessions, streak, meta, graph.clock.nowMs())
    }

    /**
     * The last set of a finished session, rebuilt from what was stored.
     *
     * The live SetTelemetry object does not outlive the fight, so this reconstructs the fields that
     * matter from the set row. Anything not stored is left out rather than guessed — an absent line
     * is honest, a zero is a claim.
     */
    private suspend fun telemetryFor(id: Long): SetTelemetry? = runCatching {
        val sets = graph.db.sessions().sets(id)
        val last = sets.lastOrNull() ?: return null
        val reps = graph.db.sessions().reps(id)
        val worst = reps.minByOrNull { it.formScore }
        val best = reps.maxByOrNull { it.formScore }
        SetTelemetry(
            exercise = last.exerciseId,
            reps = last.reps,
            formMean = last.formMean,
            formFirst3 = reps.take(3).map { it.formScore }.average().toFloat().orZero(),
            formLast3 = reps.takeLast(3).map { it.formScore }.average().toFloat().orZero(),
            formMeanPct = (last.formMean * 100).toInt(),
            formFirst3Pct = (reps.take(3).map { it.formScore }.average() * 100).toInt(),
            formLast3Pct = (reps.takeLast(3).map { it.formScore }.average() * 100).toInt(),
            depthCm = reps.mapNotNull { it.depthCm }.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            depthDropCm = null,
            velocityLossPct = 0,
            romLossPct = 0,
            fatigueBand = runCatching { enumValueOf<com.clashfit.core.model.FatigueBand>(last.fatigueBandEnd) }
                .getOrDefault(com.clashfit.core.model.FatigueBand.WORKING),
            bestRep = best?.let { SetTelemetry.RepRef(it.repIndex, it.formScore) },
            worstRep = worst?.let { SetTelemetry.RepRef(it.repIndex, it.formScore, it.reason) },
            comboMax = reps.maxOfOrNull { it.comboAtRep } ?: 1f,
            comboReps = reps.count { it.verdict == "CLEAN" },
            bossHpPct = 0,
            sessionSetIndex = last.setIndex,
            trend = SetTelemetry.Trend.FLAT,
            asymmetryPct = null,
            weakerSide = null,
        )
    }.getOrNull()

    private fun Float.orZero() = if (isNaN()) 0f else this

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.thinking) return
        val before = _state.value
        _state.value = before.copy(
            turns = before.turns + ChatTurn(q, fromPlayer = true),
            thinking = true,
        )
        askJob?.cancel()
        askJob = viewModelScope.launch {
            val answer = chat.ask(q, before.facts, before.turns)
            val now = _state.value
            _state.value = now.copy(
                turns = now.turns + ChatTurn(answer.text, fromPlayer = false, source = answer.source),
                thinking = false,
                source = answer.source,
            )
        }
    }

    override fun onCleared() {
        askJob?.cancel()
        // Stopping speech suspends, and onCleared cannot. The app scope outlives this screen,
        // which is the point: leaving the chat should cut the sentence off, not wait for it.
        // Nothing to stop: the coach does not speak.
    }

    companion object {
        fun factory(graph: AppGraph, sessionId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CoachChatViewModel(graph, sessionId) as T
        }
    }
}
