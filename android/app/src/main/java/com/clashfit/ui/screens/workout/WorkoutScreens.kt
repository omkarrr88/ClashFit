package com.clashfit.ui.screens.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.clashfit.AppGraph
import com.clashfit.core.model.GameMode
import com.clashfit.data.WorkoutEntity
import com.clashfit.engine.summary.WeightUnit
import com.clashfit.data.WorkoutSetEntity
import com.clashfit.ui.components.AppCard
import com.clashfit.ui.components.EmptyState
import com.clashfit.ui.components.AppIcons
import com.clashfit.ui.components.FilterPill
import com.clashfit.ui.components.InnerDivider
import com.clashfit.ui.components.ListGroup
import com.clashfit.ui.components.PrimaryButton
import com.clashfit.ui.components.ScreenScaffold
import com.clashfit.ui.components.SecondaryButton
import com.clashfit.ui.nav.CoachChat
import com.clashfit.ui.components.NavRow
import com.clashfit.ui.components.SectionGap
import com.clashfit.ui.components.StatTile
import com.clashfit.ui.nav.Session
import com.clashfit.ui.nav.WorkoutHome
import com.clashfit.ui.nav.WorkoutSetup
import com.clashfit.ui.screens.picker.FEATURED_EXERCISES
import com.clashfit.ui.theme.Ember
import com.clashfit.ui.theme.Ink
import com.clashfit.ui.theme.InkFaint
import com.clashfit.ui.theme.InkMuted
import com.clashfit.ui.theme.Panel
import com.clashfit.ui.theme.PanelLift
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The gym log.
 *
 * Everything a person does in a gym that this app can honestly measure: pick the movement, say
 * what is on the bar, and let the same referee that runs a boss fight count the reps and grade the
 * shape of them. The one thing it adds over an ordinary logging app is that the number of reps was
 * not typed by the person claiming them.
 *
 * It banks nothing. No experience, no streak, no leaderboard. That is deliberate — a private log
 * is a different promise from a scoreboard, and mixing them would mean either policing the log or
 * polluting the board.
 */

@Composable
fun WorkoutHomeScreen(graph: AppGraph, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val recent by graph.db.workouts().recentWorkouts(20)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var rows by remember { mutableStateOf<Map<Long, List<WorkoutSetEntity>>>(emptyMap()) }

    LaunchedEffect(recent) {
        rows = if (recent.isEmpty()) emptyMap()
        else runCatching {
            graph.db.workouts().setsForWorkouts(recent.map { it.id }).groupBy { it.workoutId }
        }.getOrDefault(emptyMap())
    }

    ScreenScaffold(title = "Workout") { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text(
                "Your own sets, in your own gym, with a coach watching. The camera counts the reps " +
                    "and grades the shape of them, and tells you what to change while you can still " +
                    "change it; you say what was on the bar.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
            )
            SectionGap(10)
            Text(
                "Same camera as a fight, different job. In Battle it referees — it rules on a rep " +
                    "and says nothing else. Here it coaches. Nothing in this tab touches the " +
                    "leaderboard.",
                style = MaterialTheme.typography.bodySmall,
                color = InkFaint,
            )
            SectionGap(20)
            NavRow(
                "Ask the coach",
                { nav.navigate(CoachChat()) },
                icon = AppIcons.Bolt,
                supporting = "About your form, your fatigue and what to train next",
            )
            SectionGap(16)

            PrimaryButton("Start a workout", Modifier.fillMaxWidth()) {
                scope.launch {
                    val id = graph.db.workouts().startWorkout(
                        WorkoutEntity(startedAtMs = graph.clock.nowMs()),
                    )
                    nav.navigate(WorkoutSetup(workoutId = id))
                }
            }

            SectionGap(28)
            Text("Previous sessions", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            SectionGap(12)

            if (recent.isEmpty()) {
                EmptyState(
                    "Nothing logged yet",
                    "Your first set writes itself down here, with the weight and the form score.",
                    icon = AppIcons.Grid,
                )
            } else {
                ListGroup {
                    recent.forEachIndexed { i, w ->
                        val sets = rows[w.id].orEmpty()
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    sets.map { it.exerciseId }.distinct()
                                        .joinToString(", ") { prettyExercise(it) }
                                        .ifBlank { "No sets" },
                                    style = MaterialTheme.typography.bodyMedium, color = Ink,
                                )
                                Text(
                                    "${sets.size} ${if (sets.size == 1) "set" else "sets"} · " +
                                        "${sets.sumOf { it.reps }} reps",
                                    style = MaterialTheme.typography.labelSmall, color = InkFaint,
                                )
                            }
                            Text(
                                dateLabel(w.startedAtMs),
                                style = MaterialTheme.typography.labelSmall, color = InkFaint,
                            )
                        }
                        if (i < recent.lastIndex) InnerDivider()
                    }
                }
            }
        }
    }
}

/**
 * Choose the movement and say what is on the bar.
 *
 * The two placeholders are the whole point of the screen. What you lifted last time and how many
 * reps you got are shown in the fields you are about to fill, because that is the moment the
 * number matters — deciding what to load. A history page two taps away is a record; a number in
 * the box you are typing into is a target.
 */
@Composable
fun WorkoutSetupScreen(graph: AppGraph, nav: NavHostController, workoutId: Long, preselect: String?) {
    val scope = rememberCoroutineScope()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle(
        initialValue = com.clashfit.data.Prefs.Settings(),
    )
    val unit = WeightUnit.of(settings.weightUnitLbs)

    var exerciseId by remember { mutableStateOf(preselect ?: FEATURED_EXERCISES.first()) }
    var typed by remember { mutableStateOf("") }
    var last by remember { mutableStateOf<WorkoutSetEntity?>(null) }
    var doneThisWorkout by remember { mutableStateOf(0) }
    var heaviestKg by remember { mutableStateOf<Float?>(null) }

    // Re-read whenever the exercise changes, and whenever we come back from a finished set.
    LaunchedEffect(exerciseId, workoutId) {
        val dao = graph.db.workouts()
        last = runCatching { dao.lastSetOf(exerciseId, workoutId) }.getOrNull()
        doneThisWorkout = runCatching { dao.setCountOf(workoutId, exerciseId) }.getOrDefault(0)
        heaviestKg = runCatching { dao.heaviestKg(exerciseId) }.getOrNull()
        typed = ""
    }

    // Inside this workout the weight you already used beats the weight from last week: you are
    // unlikely to change the plates between sets, and if you did, you will type it.
    val sameWorkoutLast = last
    val suggestedKg = sameWorkoutLast?.weightKg

    ScreenScaffold(title = "Set ${doneThisWorkout + 1}", onBack = { nav.popBackStack() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Text("Movement", style = MaterialTheme.typography.labelMedium, color = InkMuted)
            SectionGap(10)
            FEATURED_EXERCISES.forEach { id ->
                val selected = id == exerciseId
                AppCard(
                    Modifier.fillMaxWidth(),
                    onClick = { exerciseId = id },
                    container = if (selected) PanelLift else Panel,
                ) {
                    Text(
                        prettyExercise(id),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) Ink else InkMuted,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            SectionGap(20)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Weight", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WeightUnit.entries.forEach { u ->
                        FilterPill(
                            u.label,
                            selected = u == unit,
                            onClick = { scope.launch { graph.prefs.setWeightUnitLbs(u == WeightUnit.LBS) } },
                        )
                    }
                }
            }
            SectionGap(10)
            OutlinedTextField(
                value = typed,
                onValueChange = { new -> typed = new.filter { it.isDigit() || it == '.' }.take(6) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                // The placeholder is last time's number, so the field is never empty of meaning.
                placeholder = {
                    Text(
                        if (suggestedKg != null) "${unit.show(suggestedKg)} ${unit.label} last time"
                        else "Bodyweight, or type a number",
                        color = InkFaint,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            SectionGap(14)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    sameWorkoutLast?.let { "${it.reps}" } ?: "—",
                    "Last reps", Modifier.weight(1f), color = Ember,
                )
                StatTile(
                    sameWorkoutLast?.let { unit.show(it.weightKg) } ?: "—",
                    "Last ${unit.label}", Modifier.weight(1f),
                )
                StatTile(
                    heaviestKg?.let { unit.show(it) } ?: "—",
                    "Best ${unit.label}", Modifier.weight(1f),
                )
            }
            if (sameWorkoutLast != null) {
                SectionGap(10)
                Text(
                    "Beat ${sameWorkoutLast.reps} reps at ${unit.show(sameWorkoutLast.weightKg)} " +
                        "${unit.label} and this set is progress.",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted,
                )
            }

            SectionGap(24)
            PrimaryButton("Start set ${doneThisWorkout + 1}", Modifier.fillMaxWidth()) {
                val shown = typed.toFloatOrNull()
                val kg = when {
                    shown != null -> unit.toKg(shown)
                    suggestedKg != null -> suggestedKg
                    else -> 0f
                }
                nav.navigate(
                    Session(
                        mode = GameMode.WORKOUT.name,
                        exerciseId = exerciseId,
                        workoutId = workoutId,
                        weightKg = kg,
                    ),
                )
            }
            SectionGap(12)
            SecondaryButton("Finish workout", Modifier.fillMaxWidth()) {
                scope.launch {
                    val dao = graph.db.workouts()
                    // A visit with no sets in it is not a workout; it is a tap. Deleting keeps the
                    // history a list of things that happened.
                    if (dao.setCount(workoutId) == 0) dao.deleteWorkout(workoutId)
                    else dao.finishWorkout(workoutId, graph.clock.nowMs())
                    nav.popBackStack(WorkoutHome, inclusive = false)
                }
            }
        }
    }
}

/** Title case out of an id, so the four read as movements rather than as keys. */
internal fun prettyExercise(id: String): String =
    id.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

private fun dateLabel(ms: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

fun NavGraphBuilder.workoutRoutes(graph: AppGraph, nav: NavHostController) {
    composable<WorkoutHome> { WorkoutHomeScreen(graph, nav) }
    composable<WorkoutSetup> { entry ->
        val route = entry.toRoute<WorkoutSetup>()
        WorkoutSetupScreen(graph, nav, route.workoutId, route.exerciseId)
    }
}
