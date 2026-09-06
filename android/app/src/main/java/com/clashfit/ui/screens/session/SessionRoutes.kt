package com.clashfit.ui.screens.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.clashfit.AppGraph
import com.clashfit.core.model.GameMode
import com.clashfit.perception.CameraPermissionGate
import com.clashfit.ui.nav.Home
import com.clashfit.ui.nav.Session
import com.clashfit.ui.nav.Summary
import com.clashfit.ui.nav.WorkoutSetup

/**
 * Session and Summary destinations. The camera, coach and audio implementations are assembled
 * by [SessionWiring] per screen; the session screen itself only sees the [SessionDeps] ports.
 */
fun NavGraphBuilder.sessionRoutes(graph: AppGraph, nav: NavHostController) {
    composable<Session> { entry ->
        val route = entry.toRoute<Session>()
        val args = SessionArgs(
            mode = runCatching { GameMode.valueOf(route.mode) }.getOrDefault(GameMode.BOSS_FIGHT),
            exerciseId = route.exerciseId,
            casual = route.casual,
            ghostId = route.ghostId,
            durationSec = route.durationSec,
            replay = route.replay,
            workoutId = route.workoutId,
            weightKg = route.weightKg,
        )
        val owner = LocalLifecycleOwner.current
        val deps = remember(route, owner) { SessionWiring.deps(graph, owner, replay = route.replay) }
        CameraPermissionGate {
            SessionScreen(
                graph, deps, args,
                onSummary = { id ->
                    when {
                        // A gym set does not get a victory screen. It goes back to the setup for
                        // the next set, with the exercise still chosen and the count moved on, so
                        // the loop between sets is one tap rather than four.
                        route.workoutId > 0L -> nav.navigate(
                            WorkoutSetup(route.workoutId, route.exerciseId),
                        ) { popUpTo(WorkoutSetup::class) { inclusive = true } }
                        // A pass-the-phone turn goes back to the board.
                        graph.playHub.roster.value != null -> nav.popBackStack()
                        else -> nav.navigate(Summary(id)) { popUpTo(Home) }
                    }
                },
                onExit = {
                    if (route.workoutId > 0L) nav.popBackStack()
                    else nav.popBackStack(Home, inclusive = false)
                },
            )
        }
    }
    composable<Summary> { entry ->
        val route = entry.toRoute<Summary>()
        SummaryScreen(
            graph, route.sessionId,
            onHome = { nav.popBackStack(Home, inclusive = false) },
            onAgain = { s ->
                nav.navigate(Session(mode = s.mode, exerciseId = s.exerciseId, casual = s.casual, ghostId = s.ghostId)) { popUpTo(Home) }
            },
            onAskCoach = { id -> nav.navigate(com.clashfit.ui.nav.CoachChat(sessionId = id)) },
        )
    }
}


