package com.clashfit.ui.nav

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.clashfit.ui.components.AppIcons
import com.clashfit.ui.theme.LocalUiHaptics
import com.clashfit.ui.theme.Ember
import com.clashfit.ui.theme.Ground2
import com.clashfit.ui.theme.InkMuted
import com.clashfit.ui.theme.Panel
import kotlin.reflect.KClass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow

/**
 * The four top-level destinations. Every other screen belongs to exactly one of them, which is
 * how the bar knows which tab to light when you are three screens deep.
 *
 * The social tab is called Leaderboard because that is what people open it for. It still holds
 * friends, badges and challenges underneath, but naming a tab after the hub rather than after the
 * thing in the hub makes somebody read a label and guess.
 *
 * Library left the bar and moved under Train. Choosing what to train is part of training, and a
 * permanent tab for a list you visit once a week was spending a fifth of the bar on browsing.
 */
enum class Tab(val label: String, val icon: ImageVector, val root: Route, val owns: Set<KClass<out Route>>) {
    BATTLE(
        "Battle", AppIcons.Bolt, Home,
        setOf(Home::class, Modes::class, ExercisePicker::class, Session::class, Summary::class,
            DuelLobby::class, RaidRoom::class, Roster::class,
            Library::class, ExerciseDetail::class,
            // The health tools are opened from this tab, so this tab owns them. A route belongs to
            // exactly one tab: reachable from two, the bar lights whichever one the owns-set
            // happens to name and disagrees with the stack you are actually in.
            RunHome::class, RunActive::class, RunSummary::class, Alarms::class, AlarmEdit::class,
            Breathing::class, Posture::class, Desk::class, Clinic::class),
    ),

    /**
     * The gym log, next to the battles and separate from them.
     *
     * Its own tab rather than a corner of Battle because the two are opposite errands: one is
     * something you play, the other is something you record. Sharing a tab would mean every visit
     * to the gym starting with a screen full of game modes.
     */
    WORKOUT(
        "Workout", AppIcons.Grid, WorkoutHome,
        setOf(WorkoutHome::class, WorkoutSetup::class, CoachChat::class),
    ),

    LEADERBOARD(
        "Leaderboard", AppIcons.Trophy, Compete,
        setOf(Compete::class, Leaderboard::class, Friends::class, Achievements::class,
            Rewards::class, Weekly::class, Challenge::class),
    ),
    PROGRESS(
        "Progress", AppIcons.Chart, Progress,
        setOf(Progress::class, Streaks::class, History::class, Character::class, Ghosts::class),
    ),
    PROFILE(
        "Profile", AppIcons.Person, You,
        setOf(You::class, Settings::class, Privacy::class, Preflight::class,
            Account::class, About::class, Help::class, BossPreview::class),
    );

    companion object {
        fun owning(destination: NavDestination?): Tab? =
            destination?.let { d -> entries.firstOrNull { tab -> tab.owns.any { d.hasRoute(it) } } }
    }
}

/**
 * Screens where the bar would be in the way: a fight, a run in progress, a hold, a breathing
 * drill. The bar hides and the screen takes the whole display.
 */
private val IMMERSIVE: Set<KClass<out Route>> = setOf(
    Splash::class, Onboarding::class, SignUp::class, SignIn::class, ResetPassword::class, ProfileSetup::class, CameraPrimer::class,
    Session::class, RunActive::class,
    DuelLobby::class, RaidRoom::class, Roster::class, Breathing::class, Posture::class,
)

private fun NavDestination.isImmersive(): Boolean = IMMERSIVE.any { hasRoute(it) }

/**
 * Material's compact-to-medium breakpoint. Below it a bottom bar is right; at and above it the
 * navigation belongs down the side. This matters more than it used to: an app targeting SDK 36
 * or later does not get to lock orientation on a large screen, so the tablet and the unfolded
 * foldable will be landscape whatever the manifest says.
 */
private val WIDE_BREAKPOINT = 600.dp

/**
 * Switch tabs the way Android expects: each tab keeps its own stack, tapping a tab you are
 * already on returns you to its root, and the stack under the home tab is never discarded.
 */
fun NavHostController.switchTo(tab: Tab) {
    navigate(tab.root) {
        popUpTo<Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The shell that wraps every routed screen: a bottom bar on a phone, a rail on anything wider. */
@Composable
fun MainScaffold(nav: NavHostController, content: @Composable (PaddingValues) -> Unit) {
    val entry by nav.currentBackStackEntryAsState()
    val haptics = LocalUiHaptics.current
    val destination = entry?.destination
    val current = Tab.owning(destination)
    val showBar = destination != null && !destination.isImmersive()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE_BREAKPOINT

        // Zero content insets on this Scaffold: the bar and the rail pad themselves against the
        // system bars, each screen's own top bar pads against the status bar, and AppNavHost
        // consumes what this hands down so the nested scaffold never pads the same edge twice.
        val scaffold: @Composable (@Composable () -> Unit) -> Unit = { bottom ->
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = bottom,
                content = content,
            )
        }

        if (wide && showBar) {
            Row(Modifier.fillMaxSize()) {
                // NavigationRail applies its own system-bar insets.
                NavigationRail(containerColor = Ground2, contentColor = InkMuted) {
                    Tab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = tab == current,
                            onClick = { haptics.select(); nav.switchTo(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { TabLabel(tab.label) },
                            alwaysShowLabel = true,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Ember,
                                selectedTextColor = Ember,
                                indicatorColor = Panel,
                                unselectedIconColor = InkMuted,
                                unselectedTextColor = InkMuted,
                            ),
                        )
                    }
                }
                scaffold {}
            }
        } else {
            scaffold {
                if (showBar) {
                    NavigationBar(containerColor = Ground2, contentColor = InkMuted, tonalElevation = 0.dp) {
                        Tab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == current,
                                onClick = { haptics.select(); nav.switchTo(tab) },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { TabLabel(tab.label) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Ember,
                                    selectedTextColor = Ember,
                                    indicatorColor = Panel,
                                    unselectedIconColor = InkMuted,
                                    unselectedTextColor = InkMuted,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A tab label that stays on one line, whole.
 *
 * Five tabs give each label a fifth of the screen, where four gave it a quarter. "Leaderboard" is
 * the longest word in the bar and it did not survive the fifth tab: it drew as "Leaderbo…", which
 * is worse than a smaller word — the point of a label is that it can be read.
 *
 * Pinning the size against the system font scale was not enough, because it still did not fit at
 * scale 1. So the label shrinks to fit instead. Each layout that reports an overflow steps the
 * size down and asks again; the size only ever decreases, so it settles rather than oscillates,
 * and it holds for any label, any language and any screen width rather than for the one word
 * somebody measured. The floor stops it shrinking into illegibility, and the ellipsis is what
 * happens if a label is so long that even the floor cannot hold it.
 *
 * Everything else on every screen still scales the whole way with the system font size, which is
 * where it actually helps — a tab label is read once to learn the bar and then navigated by
 * position and icon.
 */
@Composable
private fun TabLabel(text: String) {
    val scale = LocalDensity.current.fontScale
    val base = MaterialTheme.typography.labelMedium
    // Expressed in sp, which the renderer multiplies by the scale again — so dividing by the
    // scale here pins the drawn size, rather than merely reducing it a little.
    val pinned = if (scale > 1f) base.copy(fontSize = base.fontSize * (1f / scale)) else base
    BasicText(
        text = text,
        style = pinned.copy(color = LocalContentColor.current),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = LABEL_FLOOR,
            maxFontSize = pinned.fontSize,
            stepSize = 0.25.sp,
        ),
    )
}

/** Small enough to fit "Leaderboard" on a five-tab bar, large enough to still be a word. */
private val LABEL_FLOOR = 9.sp
