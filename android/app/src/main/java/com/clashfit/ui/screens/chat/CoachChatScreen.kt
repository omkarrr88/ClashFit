package com.clashfit.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.clashfit.ui.nav.CoachChat
import com.clashfit.AppGraph
import com.clashfit.coach.chat.ChatTurn
import com.clashfit.core.model.CoachSource
import com.clashfit.ui.components.AppCard
import com.clashfit.ui.components.AppIcons
import com.clashfit.ui.components.ScreenScaffold
import com.clashfit.ui.components.Tag
import com.clashfit.ui.theme.Ember
import com.clashfit.ui.theme.EmberTint
import com.clashfit.ui.theme.Ground
import com.clashfit.ui.theme.Ink
import com.clashfit.ui.theme.InkFaint
import com.clashfit.ui.theme.InkMuted
import com.clashfit.ui.theme.Panel
import com.clashfit.ui.theme.Rule
import com.clashfit.ui.theme.Success

/**
 * Ask the coach about your own numbers.
 *
 * The whole point is the constraint, not the chat: it may only answer from a fact sheet built out
 * of what the camera actually measured. It cannot invent a depth, congratulate you on a streak you
 * never had, or be confidently wrong about a set it never saw — because the numbers it is allowed
 * to use are handed to it, and it is told to say so plainly when they do not cover the question.
 *
 * Opened with a session id it answers about the set you just finished; without one it answers
 * about your history. The badge at the top says which voice is speaking — the model on this phone,
 * the cloud model you opted into, or the template bank — because "an AI said it" and "a lookup
 * table said it" are different claims and the player is owed the difference.
 */
@Composable
fun CoachChatScreen(graph: AppGraph, sessionId: Long, onBack: () -> Unit) {
    val vm: CoachChatViewModel = viewModel(factory = CoachChatViewModel.factory(graph, sessionId))
    val state by vm.state.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the conversation down. A new answer that lands off the bottom of the screen reads as
    // nothing having happened.
    LaunchedEffect(state.turns.size, state.thinking) {
        val last = state.turns.size + if (state.thinking) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    ScreenScaffold(
        title = "Coach",
        onBack = onBack,
        actions = { SourceBadge(state.source, state.ready) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.turns.isEmpty()) {
                    item(key = "intro") { Opening(state.afterSet) }
                }
                items(state.turns.size) { i -> Bubble(state.turns[i]) }
                if (state.thinking) item(key = "thinking") { Thinking() }
            }

            // The starters are the demo path and the everyday path at once: nobody wants to type a
            // question on a phone, and these are the six questions the fact sheet can always answer.
            if (state.starters.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.starters) { q ->
                        Starter(q, enabled = !state.thinking) { vm.ask(q) }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 20.dp).padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask about your training", color = InkFaint) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        vm.ask(typed); typed = ""
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                        focusedBorderColor = Ember, unfocusedBorderColor = Rule,
                        focusedTextColor = Ink, unfocusedTextColor = Ink, cursorColor = Ember,
                    ),
                )
                IconButton(
                    onClick = { vm.ask(typed); typed = "" },
                    enabled = typed.isNotBlank() && !state.thinking,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        AppIcons.Bolt,
                        contentDescription = "Ask",
                        tint = if (typed.isNotBlank() && !state.thinking) Ember else InkFaint,
                    )
                }
            }
        }
    }
}

/** Which voice is answering. Never hidden: the three are different claims. */
@Composable
private fun SourceBadge(source: CoachSource, ready: Boolean) {
    if (!ready) {
        Tag("Reading your numbers", color = InkMuted)
        return
    }
    when (source) {
        CoachSource.LLM -> Tag("On this phone", color = Success)
        CoachSource.CLOUD -> Tag("Cloud", color = Ember)
        CoachSource.TEMPLATE -> Tag("Built-in", color = InkMuted)
    }
}

@Composable
private fun Opening(afterSet: Boolean) {
    AppCard(Modifier.fillMaxWidth(), padding = 16) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (afterSet) "Ask about that set" else "Ask about your training",
                style = MaterialTheme.typography.titleMedium, color = Ink,
            )
            Text(
                "It answers from what the camera measured — depth, range, tempo, fatigue, and the " +
                    "sessions behind them. If the numbers do not cover a question it says so " +
                    "rather than guessing.",
                style = MaterialTheme.typography.bodySmall, color = InkMuted,
            )
        }
    }
}

/** One turn. The player is on the right in ember; the coach is on the left on a panel. */
@Composable
private fun Bubble(turn: ChatTurn) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (turn.fromPlayer) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier.widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (turn.fromPlayer) 16.dp else 4.dp,
                        bottomEnd = if (turn.fromPlayer) 4.dp else 16.dp,
                    ),
                )
                .background(if (turn.fromPlayer) EmberTint else Panel)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                turn.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (turn.fromPlayer) Ember else Ink,
            )
        }
    }
}

@Composable
private fun Thinking() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = Ember, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text("Reading your numbers…", style = MaterialTheme.typography.bodySmall, color = InkMuted)
    }
}

@Composable
private fun Starter(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) Panel else Ground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) Ink else InkFaint,
        )
    }
}

fun NavGraphBuilder.coachChatRoutes(graph: AppGraph, nav: NavHostController) {
    composable<CoachChat> { entry ->
        val route = entry.toRoute<CoachChat>()
        CoachChatScreen(graph, sessionId = route.sessionId, onBack = { nav.navigateUp() })
    }
}
