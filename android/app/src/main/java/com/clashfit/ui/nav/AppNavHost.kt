package com.clashfit.ui.nav

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import com.clashfit.ui.theme.LocalUiHaptics
import com.clashfit.ui.theme.UiHaptics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clashfit.AppGraph
import com.clashfit.alarm.alarmRoutes
import com.clashfit.core.model.GameMode
import com.clashfit.data.Prefs
import com.clashfit.run.runRoutes
import com.clashfit.ui.screens.breathing.breathingRoutes
import com.clashfit.ui.screens.chat.coachChatRoutes
import com.clashfit.ui.screens.challenge.challengeRoutes
import com.clashfit.ui.screens.duel.duelRoutes
import com.clashfit.ui.screens.ghosts.ghostsRoutes
import com.clashfit.ui.screens.home.HomeScreen
import com.clashfit.ui.screens.onboarding.CameraPrimerScreen
import com.clashfit.ui.screens.onboarding.ProfileSetupScreen
import com.clashfit.ui.screens.onboarding.ResetPasswordScreen
import com.clashfit.ui.screens.onboarding.SignInScreen
import com.clashfit.ui.screens.onboarding.SignUpScreen
import com.clashfit.ui.screens.onboarding.WelcomeScreen
import com.clashfit.ui.screens.progress.ProgressScreen
import com.clashfit.ui.screens.workout.workoutRoutes
import com.clashfit.ui.screens.roster.rosterRoutes
import com.clashfit.ui.screens.session.sessionRoutes
import com.clashfit.ui.screens.shellRoutes
import com.clashfit.ui.screens.about.AboutScreen
import com.clashfit.ui.screens.about.HelpScreen
import com.clashfit.ui.screens.social.AccountScreen
import com.clashfit.ui.screens.social.AchievementsScreen
import com.clashfit.ui.screens.social.FriendsScreen
import com.clashfit.ui.screens.social.LeaderboardScreen
import com.clashfit.ui.screens.social.WeeklyScreen
import com.clashfit.ui.screens.splash.SplashScreen
import com.clashfit.ui.screens.you.YouScreen
import com.clashfit.ui.theme.Motion
import kotlinx.coroutines.flow.Flow
import com.clashfit.ui.screens.session.bossPreviewRoutes
import com.clashfit.ui.screens.social.competeRoutes
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.clashfit.ui.screens.zombierun.ZombieRunScreen
import com.clashfit.ui.screens.social.RewardsScreen

/**
 * The whole navigation graph, inside the app shell. The shell draws the bottom bar and hides it
 * on immersive screens; the graph stays flat so every route keeps working, and each tab keeps
 * its own back stack through saveState/restoreState in [switchTo].
 *
 * First run: Splash → Onboarding (welcome) → SignUp / SignIn → ProfileSetup → CameraPrimer → Home,
 * with the welcome stack popped so back from Home leaves the app. docs/02-APP-FLOW.md §2
 */
@Composable
fun AppNavHost(
    graph: AppGraph,
    deskExerciseId: Flow<String?> = kotlinx.coroutines.flow.emptyFlow(),
    shortcutAction: Flow<String?> = kotlinx.coroutines.flow.emptyFlow(),
    nav: NavHostController = rememberNavController(),
) {
    val deskExerciseIdValue = deskExerciseId.collectAsState(initial = null).value
    val shortcutActionValue = shortcutAction.collectAsState(initial = null).value
    val settings by graph.prefs.settings.collectAsState(initial = Prefs.Settings())

    LaunchedEffect(deskExerciseIdValue) {
        if (deskExerciseIdValue != null) {
            nav.navigate(Session(mode = GameMode.BOSS_FIGHT.name, exerciseId = deskExerciseIdValue, casual = true, durationSec = 60))
        }
    }

    LaunchedEffect(shortcutActionValue) {
        if (shortcutActionValue != null) {
            when (shortcutActionValue) {
                "start_fight" -> nav.navigate(ExercisePicker(GameMode.BOSS_FIGHT.name))
                "run" -> nav.navigate(RunHome)
            }
        }
    }

    /** Leave the first-run stack behind for good. */
    fun enterApp() = nav.navigate(Home) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
    fun afterSignIn() = if (settings.onboarded) enterApp() else nav.navigate(ProfileSetup) { popUpTo<Onboarding> { inclusive = true } }

    // One provider around every routed screen, so a button in any of them can speak through the
    // same motor the fight uses — and obey the same Haptics switch.
    val uiHaptics = remember(graph) {
        object : UiHaptics {
            override fun tap() = graph.haptics.tap()
            override fun select() = graph.haptics.select()
            override fun reward() = graph.haptics.reward()
        }
    }
    CompositionLocalProvider(LocalUiHaptics provides uiHaptics) {
    // (the scaffold body keeps its original indentation; the provider wraps it whole)
    MainScaffold(nav) { padding ->
        NavHost(
            navController = nav,
            startDestination = Splash,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = ScreenMotion.enter(settings.reduceMotion),
            exitTransition = ScreenMotion.exit(settings.reduceMotion),
            popEnterTransition = ScreenMotion.popEnter(settings.reduceMotion),
            popExitTransition = ScreenMotion.popExit(settings.reduceMotion),
        ) {
            composable<Splash> {
                SplashScreen(graph) { first -> nav.navigate(first) { popUpTo<Splash> { inclusive = true } } }
            }

            // ── first run ──
            composable<Onboarding> {
                val scope = rememberCoroutineScope()
                WelcomeScreen(
                    onCreateAccount = { nav.navigate(SignUp) },
                    onSignIn = { nav.navigate(SignIn) },
                    onSkip = {
                        scope.launch {
                            graph.prefs.setGuest(true)
                            nav.navigate(ProfileSetup) { popUpTo<Onboarding> { inclusive = true } }
                        }
                    },
                )
            }
            composable<SignUp> {
                SignUpScreen(
                    graph,
                    onBack = { nav.navigateUp() },
                    onSignIn = { nav.navigate(SignIn) { popUpTo<SignUp> { inclusive = true } } },
                    onDone = { nav.navigate(ProfileSetup) { popUpTo<Onboarding> { inclusive = true } } },
                )
            }
            composable<SignIn> {
                SignInScreen(
                    graph,
                    onBack = { nav.navigateUp() },
                    onSignUp = { nav.navigate(SignUp) { popUpTo<SignIn> { inclusive = true } } },
                    onForgot = { nav.navigate(ResetPassword) },
                    onDone = { afterSignIn() },
                )
            }
            composable<ResetPassword> { ResetPasswordScreen(graph, onBack = { nav.navigateUp() }) }
            composable<ProfileSetup> { ProfileSetupScreen(graph, onDone = { nav.navigate(CameraPrimer) { popUpTo<ProfileSetup> { inclusive = true } } }) }
            composable<CameraPrimer> { CameraPrimerScreen(onDone = { enterApp() }) }

            // ── the four tab roots ──
            composable<Home> { HomeScreen(graph, nav) }
            composable<Progress> { ProgressScreen(graph, nav) }
            workoutRoutes(graph, nav)
            composable<You> { YouScreen(graph, nav) }
            // Library's root is registered with its detail screen in libraryRoutes, via shellRoutes.

            // ── social and progression ──
            composable<Leaderboard> {
                LeaderboardScreen(graph, onBack = { nav.navigateUp() }, onSignIn = { nav.navigate(SignIn) }, onFriends = { nav.navigate(Friends) })
            }
            composable<Friends> { FriendsScreen(graph, onBack = { nav.navigateUp() }) }
            composable<Achievements> { AchievementsScreen(graph, onBack = { nav.navigateUp() }) }
            composable<Rewards> { RewardsScreen(graph, onBack = { nav.navigateUp() }) }
            composable<Weekly> {
                WeeklyScreen(
                    graph, onBack = { nav.navigateUp() },
                    onFight = { nav.navigate(ExercisePicker(GameMode.BOSS_FIGHT.name)) },
                    onLeaderboard = { nav.navigate(Leaderboard) },
                )
            }
            composable<About> {
                AboutScreen(
                    graph, nav,
                    onPrivacy = { nav.navigate(Privacy) },
                    onHelp = { nav.navigate(Help) },
                )
            }
            composable<Help> {
                HelpScreen(graph, nav, onStart = { nav.navigate(ExercisePicker(GameMode.BOSS_FIGHT.name)) })
            }
            composable<Account> {
                AccountScreen(
                    graph,
                    onBack = { nav.navigateUp() },
                    onSignedOut = { nav.navigate(Onboarding) { popUpTo(0) { inclusive = true } } },
                    onSignIn = { nav.navigate(Onboarding) },
                )
            }

            shellRoutes(graph, nav)      // modes · picker · library · character · streaks · history · settings · privacy · preflight · clinic · posture · desk
            sessionRoutes(graph, nav)    // calibration → fight → rest → victory, and the summary
            bossPreviewRoutes(graph, nav) // the boss on its own, to check the renderer at a venue
            competeRoutes(graph, nav)    // the social hub: boards, friends, badges, challenges
            runRoutes(graph, nav)        // the run tracker
            composable<ZombieRun> { ZombieRunScreen(graph, nav) }
            alarmRoutes(graph, nav)      // the rep-gated wake-up alarm

            duelRoutes(graph, nav)       // 1v1 lobby and the raid room, phone-to-phone over Nearby
            rosterRoutes(graph, nav)     // pass the phone
            ghostsRoutes(graph, nav)     // race a recorded rep timeline
            challengeRoutes(graph, nav)  // dare codes, shared without a server
            breathingRoutes(graph, nav)  // recovery between rounds
            coachChatRoutes(graph, nav)  // ask the coach about your own numbers
        }
    }
    }
}
