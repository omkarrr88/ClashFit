package com.clashfit.ui.screens.you

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.clashfit.AppGraph
import com.clashfit.auth.AuthState
import com.clashfit.data.Prefs
import com.clashfit.meta.AchievementCatalog
import com.clashfit.ui.components.AppCard
import com.clashfit.ui.components.AppIcons
import com.clashfit.ui.components.Avatar
import com.clashfit.ui.components.Bar
import com.clashfit.ui.components.BarAction
import com.clashfit.ui.components.InnerDivider
import com.clashfit.ui.components.LevelRing
import com.clashfit.ui.components.ListGroup
import com.clashfit.ui.components.NavRow
import com.clashfit.ui.components.ScreenScaffold
import com.clashfit.ui.components.SectionGap
import com.clashfit.ui.components.SectionTitle
import com.clashfit.ui.components.StatStrip
import com.clashfit.ui.components.XpBar
import com.clashfit.ui.nav.About
import com.clashfit.ui.nav.CoachChat
import com.clashfit.ui.nav.Account
import com.clashfit.ui.nav.Achievements
import com.clashfit.ui.nav.Alarms
import com.clashfit.ui.nav.Breathing
import com.clashfit.ui.nav.Challenge
import com.clashfit.ui.nav.Clinic
import com.clashfit.ui.nav.Desk
import com.clashfit.ui.nav.Friends
import com.clashfit.ui.nav.Help
import com.clashfit.ui.nav.Leaderboard
import com.clashfit.ui.nav.Posture
import com.clashfit.ui.nav.Preflight
import com.clashfit.ui.nav.Privacy
import com.clashfit.ui.nav.RunHome
import com.clashfit.ui.nav.Settings
import com.clashfit.ui.nav.Weekly
import com.clashfit.ui.theme.AvatarPalette
import com.clashfit.ui.theme.Brass
import com.clashfit.ui.theme.Ink
import com.clashfit.ui.theme.InkMuted
import com.clashfit.ui.theme.Success
import com.clashfit.ui.nav.Compete
import com.clashfit.ui.nav.switchTo
import com.clashfit.ui.nav.Tab
import com.clashfit.ui.components.rankColour
import com.clashfit.ui.components.RankInsignia

/** The You tab: who you are, how far you have come, who you play with, and the tools. */
@Composable
fun YouScreen(graph: AppGraph, nav: NavHostController) {
    val auth by graph.auth.state.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle(initialValue = Prefs.Settings())
    val metaFlow = remember(graph) { graph.meta.state }
    val streakFlow = remember(graph) { graph.db.streak().observe() }
    val countFlow = remember(graph) { graph.db.sessions().count() }
    val meta by metaFlow.collectAsStateWithLifecycle(initialValue = null)
    val streak by streakFlow.collectAsStateWithLifecycle(initialValue = null)
    val sessionCount by countFlow.collectAsStateWithLifecycle(initialValue = 0)

    val name = (auth as? AuthState.SignedIn)?.user?.shownName() ?: "Athlete"
    val badges = meta?.unlocked?.size ?: 0

    ScreenScaffold(title = "You", actions = { BarAction(AppIcons.Gear, "Settings") { nav.navigate(Settings) } }) { padding ->
        Column(
            Modifier.fillMaxWidth().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 28.dp),
        ) {
            AppCard(Modifier.fillMaxWidth(), onClick = { nav.navigate(Account) }, padding = 18) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Avatar(name, size = 64, color = AvatarPalette.at(settings.avatarColor))
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleLarge, color = Ink)
                            Text(
                                meta?.progress?.title ?: "Recruit",
                                style = MaterialTheme.typography.titleMedium,
                                color = rankColour(meta?.progress?.level ?: 1),
                            )
                        }
                        // The emblem, not a ring with a number in it. A rank you can recognise
                        // is a rank worth climbing; a number in a circle is a database row.
                        meta?.progress?.let { RankInsignia(it.level, size = 60) }
                    }
                    Spacer(Modifier.height(16.dp))
                    meta?.progress?.let { XpBar(it, showTitle = false) }
                }
            }

            SectionGap(18)
            StatStrip(listOf("${streak?.current ?: 0}" to "Day streak", "$sessionCount" to "Sessions", "$badges" to "Badges"))

            meta?.weekly?.let { w ->
                SectionGap(20)
                AppCard(
                    Modifier.fillMaxWidth(),
                    onClick = { nav.switchTo(Tab.LEADERBOARD); nav.navigate(Weekly) },
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Weekly challenge", style = MaterialTheme.typography.labelMedium, color = InkMuted)
                                Text(w.challenge.title, style = MaterialTheme.typography.titleMedium, color = Ink)
                            }
                            Text(
                                if (w.done) "Done" else "${w.value} / ${w.challenge.target}",
                                style = MaterialTheme.typography.titleSmall, color = if (w.done) Success else Ink,
                            )
                        }
                        Bar(w.fraction, Modifier.padding(top = 12.dp), color = if (w.done) Success else MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Two pointers, to the tabs that now own these things. Switching the tab rather than
            // pushing onto this one keeps the bar honest: a route belongs to one tab, and arriving
            // at it from somewhere else used to light a tab you were not in.
            SectionGap(26)
            ListGroup {
                NavRow(
                    "Leaderboard",
                    { nav.switchTo(Tab.LEADERBOARD) },
                    icon = AppIcons.Trophy,
                    tint = Brass,
                    value = "$badges of ${AchievementCatalog.all.size}",
                    supporting = "Boards, friends, badges and challenges",
                )
                InnerDivider()
                NavRow(
                    "Health tools",
                    { nav.switchTo(Tab.BATTLE) },
                    icon = AppIcons.Heart,
                    tint = Success,
                    supporting = "Run, breathing, clinic, posture, desk and the alarm, on Train",
                )
            }

            SectionGap(26)
            SectionTitle("App")
            SectionGap(10)
            ListGroup {
                NavRow(
                    "Ask the coach",
                    { nav.navigate(CoachChat()) },
                    icon = AppIcons.Bolt,
                    supporting = "Your form and your history, answered from what was measured",
                )
                InnerDivider()
                NavRow("Account", { nav.navigate(Account) }, icon = AppIcons.Person, tint = InkMuted)
                InnerDivider()
                NavRow(
                    "System check",
                    { nav.navigate(Preflight) },
                    icon = AppIcons.Camera,
                    supporting = "Camera, pose model, audio, storage and battery",
                    tint = InkMuted,
                )
                InnerDivider()
                NavRow("Settings", { nav.navigate(Settings) }, icon = AppIcons.Gear, tint = InkMuted)
                InnerDivider()
                NavRow("Privacy", { nav.navigate(Privacy) }, icon = AppIcons.Shield, tint = InkMuted, supporting = "What stays on the phone, and what does not")
                InnerDivider()
                NavRow("How to play", { nav.navigate(Help) }, icon = AppIcons.Bolt, tint = InkMuted, supporting = "Set the phone up, and what the numbers mean")
                InnerDivider()
                NavRow("About", { nav.navigate(About) }, icon = AppIcons.Grid, tint = InkMuted, supporting = "Version, what it is built from, and who made it")
            }
        }
    }
}
