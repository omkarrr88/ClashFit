package com.clashfit.ui.screens.zombierun

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.clashfit.AppGraph
import com.clashfit.core.model.GameOutcome
import com.clashfit.core.model.ZombieRunPhase
import com.clashfit.data.ActivityKind
import com.clashfit.engine.games.ZombieRunGame
import com.clashfit.map.MapMarker
import com.clashfit.map.PlacedMarker
import com.clashfit.map.rememberRouteMapState
import com.clashfit.map.RouteMap
import com.clashfit.run.RunTracker
import com.clashfit.run.RunTrackingService
import com.clashfit.run.metresEastNorth
import com.clashfit.ui.components.ChaseGlyphs
import com.clashfit.ui.components.MapControls
import com.clashfit.ui.components.StreetMapNotice
import com.clashfit.ui.components.AppCard
import com.clashfit.ui.components.PrimaryButton
import com.clashfit.ui.components.RouteTrace
import com.clashfit.ui.components.ScreenScaffold
import com.clashfit.ui.components.SecondaryButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Color
import com.clashfit.core.model.WinBy
import com.clashfit.data.Prefs
import com.clashfit.ui.components.SectionTitle
import com.clashfit.ui.theme.Panel
import com.clashfit.ui.theme.Rule
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.clashfit.ui.components.SectionGap
import com.clashfit.ui.components.StatStrip
import com.clashfit.ui.theme.Ember
import com.clashfit.ui.theme.Gassed
import com.clashfit.ui.theme.Ground
import com.clashfit.ui.theme.Ink
import com.clashfit.ui.theme.InkFaint
import com.clashfit.ui.theme.InkMuted
import com.clashfit.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.cos
import kotlin.math.roundToInt
import android.content.Context
import com.clashfit.run.RunRepository

/**
 * ZOMBIE RUN — the outdoor chase, on a real map. `docs/33-FEATURE-ZOMBIE-RUN.md`.
 *
 * The mode is a layer over the ordinary run tracker rather than a second location pipeline: the
 * service is already the one thing allowed to hold a GPS subscription, it already filters bad
 * fixes, and running the chase on top of it means a Zombie Run also *is* a recorded activity — it
 * shows up in the feed, it earns its distance, and its route is stored exactly like any other.
 *
 * Two rules from the design document are enforced here rather than promised:
 *
 * - **The camera never opens.** Nothing on this screen touches it, so the video promise holds
 *   without qualification even in the one mode that goes online.
 * - **The mode announces itself** before the head start, in words, every time. Not a permission
 *   dialog, and not once-ever-then-forgotten: this is the one mode that downloads map tiles and
 *   the player is told each time they choose it.
 */
@Composable
fun ZombieRunScreen(graph: AppGraph, nav: NavHostController) {
    val context = LocalContext.current
    val tracker = RunTracker.getInstance()
    val runState by tracker.state.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle(initialValue = com.clashfit.data.Prefs.Settings())
    val scope = rememberCoroutineScope()

    // Collected rather than read as .value: the config store hot-reloads, and a screen holding a
    // snapshot would keep showing the numbers it was born with while the briefing beside it
    // promised different ones.
    val combat by graph.config.combat.collectAsStateWithLifecycle()
    val cfg = combat.modes.zombieRun
    // Rebuilt on start rather than remembered once, because the goal and the pack's speed are the
    // player's to set and they set them on the screen immediately before this one.
    var game by remember {
        mutableStateOf(ZombieRunGame(ZombieRunGame.ZombieRunConfig(headStartSec = cfg.headStartSec)))
    }

    var started by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf(Chase()) }

    // Leaving the chase ends the chase.
    //
    // The tracking service was started when the head start began and stopped only by the Finish
    // button on the outcome panel. Back out of a chase instead — with the system gesture, or by
    // switching tabs — and it kept running: a foreground service holding a GPS lock and a wake
    // lock for the rest of the session, writing points into a run row that could never be
    // finished. Neither is visible, which is exactly why it went unnoticed.
    //
    // Stopping here runs the service's own finish path, which discards an activity that recorded
    // nothing, so the abandoned row is cleaned up rather than left behind. `started` is read
    // through a holder because the effect is keyed on nothing and must see the value at the moment
    // the screen actually leaves.
    val running = rememberUpdatedState(started)
    DisposableEffect(Unit) {
        onDispose { if (running.value) RunTrackingService.stop(context) }
    }
    var origin by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var state by remember { mutableStateOf(game.state(0L)) }
    // The last position we were sure of, held here rather than read from the tracker each tick.
    // The service flushes its point buffer to the database every ten fixes and starts a new one,
    // so `state.points` empties roughly once a second — reading the position straight out of it
    // would stall the chase for a tick every time that happened, and a pack that freezes whenever
    // the database is written is a pack you can escape by waiting.
    var lastEastM by remember { mutableStateOf(0f) }
    var lastNorthM by remember { mutableStateOf(0f) }

    val locationOk = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    // The chase runs on its own clock at 10 Hz. GPS delivers about one fix a second, which is far
    // too coarse for a zombie to look alive; between fixes the pack keeps closing on the last
    // known position, which is also what it would really do.
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) {
            val here = runState.points.lastOrNull()
            if (here != null) {
                if (origin == null) origin = here.lat to here.lon
                origin?.let { o ->
                    val local = metresEastNorth(o.first, o.second, here.lat, here.lon)
                    lastEastM = local.eastM
                    lastNorthM = local.northM
                }
            }
            // Tick every time, fix or no fix. Losing the signal must not pause the chase.
            state = game.onTick(graph.clock.nowMs(), lastEastM, lastNorthM, runState.cadenceSpm)
            if (state.outcome != GameOutcome.RUNNING) {
                // Banked here because the chase knows about zombies and the tracking service does
                // not. Guarded on the row existing: a chase abandoned before the first fix or the
                // first step has no activity to attach a score to.
                runState.runId?.let { id ->
                    val repo = RunRepository(graph.db.runs(), graph.clock)
                    runCatching { repo.setScore(id, game.score(graph.clock.nowMs())) }
                }
                break
            }
            delay(100)
        }
    }

    // Said here as well as on the Outdoors tab, because this screen is also reachable from Modes
    // and every map in the app waits on the answer.
    if (settings.loaded && !settings.mapTilesAsked) {
        StreetMapNotice(
            onShowStreets = { scope.launch { graph.prefs.setMapTiles(true) } },
            onKeepOffline = { scope.launch { graph.prefs.setMapTiles(false) } },
        )
    }

    ScreenScaffold(title = "Zombie Run", onBack = { nav.navigateUp() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).background(Ground)
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
        ) {
            if (!started) {
                Briefing(
                    graph = graph,
                    cfg = cfg,
                    locationOk = locationOk,
                    onStart = { chase ->
                        RunTrackingService.start(context, ActivityKind.ZOMBIE_RUN)
                        game = ZombieRunGame(
                            ZombieRunGame.ZombieRunConfig(
                                headStartSec = cfg.headStartSec,
                                spawnRadiusM = cfg.spawnRadiusM,
                                captureRadiusM = cfg.captureRadiusM,
                                winBy = if (chase.byDistance) WinBy.DISTANCE else WinBy.TIME,
                                survivalSec = chase.minutes * 60,
                                winDistanceM = chase.distanceKm * 1000,
                                // The slider is in km/h because that is how a runner thinks about
                                // being chased; the engine works in metres a second.
                                zombieBaseMps = chase.zombieKph / 3.6f,
                            ),
                        )
                        goal = chase
                        // Seeded from the wall clock so two players never get the same layout, and
                        // so a chase could be replayed from its seed later.
                        game.start(graph.clock.nowMs(), graph.clock.nowMs())
                        started = true
                    },
                )
                return@Column
            }

            ChaseFeel(state, graph)
            ChaseHud(state, goal, if (state.outcome == GameOutcome.RUNNING) null else game.score(graph.clock.nowMs()))
            SectionGap(12)

            val o = origin
            val markers = if (o == null) emptyList() else buildList {
                state.zombies.filter { it.alive }.forEach { z ->
                    // How near this one is, on the scale the chase was configured with, so the
                    // figure looks more dangerous the closer it gets rather than being one flat
                    // shape until the moment it catches you.
                    val d = hypot((z.eastM - state.playerEastM).toDouble(), (z.northM - state.playerNorthM).toDouble()).toFloat()
                    val near = (1f - d / cfg.spawnRadiusM.toFloat()).coerceIn(0f, 1f)
                    add(
                        marker(
                            o, z.eastM, z.northM, radiusM = 16.0, colour = Gassed,
                            glyph = MapMarker.Glyph.ZOMBIE, closeness = near,
                        ),
                    )
                }
            }

            // One phase for the whole pack, so the zombies breathe together and quicken as the
            // nearest one closes. Tied to the same distance the heartbeat is tied to.
            val pulse by rememberInfiniteTransition(label = "pack").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = beatIntervalMs(state.nearestZombieM ?: Float.MAX_VALUE)
                        .toInt().coerceIn(320, 1500),
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "packPhase",
            )

            val mapState = rememberRouteMapState()
            AppCard(Modifier.fillMaxWidth().weight(1f), padding = 0) {
                Box(Modifier.fillMaxSize()) {
                    if (settings.mapTiles && settings.mapTilesAsked && runState.points.isNotEmpty()) {
                        var placed by remember { mutableStateOf<List<PlacedMarker>>(emptyList()) }
                        RouteMap(
                            points = runState.points,
                            modifier = Modifier.fillMaxSize(),
                            paceColoured = false,
                            follow = true,
                            interactive = true,
                            extraOverlays = markers,
                            state = mapState,
                            onMarkerLayout = { placed = it },
                        )
                        // The pack is painted over the street map rather than into it, by the same
                        // painter the tile-free trace uses, so the chase looks the same either way.
                        Canvas(Modifier.fillMaxSize()) {
                            drawIntoCanvas { canvas ->
                                placed.forEach { pm ->
                                    when (pm.marker.glyph) {
                                        MapMarker.Glyph.ZOMBIE -> ChaseGlyphs.zombie(
                                            canvas.nativeCanvas, pm.xPx, pm.yPx, pm.radiusPx,
                                            pm.marker.closeness, pulse,
                                        )
                                        MapMarker.Glyph.CIRCLE -> Unit
                                    }
                                }
                            }
                        }
                        MapControls(mapState, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                    } else {
                        // No tiles: the same chase, drawn on our own grid. The pursuers are drawn
                        // here too, because a chase you cannot see is not a chase.
                        RouteTrace(
                            points = runState.points,
                            modifier = Modifier.fillMaxSize(),
                            paceColoured = false,
                            markers = markers,
                            markerPulse = pulse,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Outcome(state, onFinish = {
                RunTrackingService.stop(context)
                nav.navigateUp()
            })
        }
    }
}


/**
 * One labelled slider: what it sets on the left, what it is set to on the right, the track under.
 *
 * The readout is beside the label rather than on the thumb because a value that rides the thumb is
 * under the finger that is dragging it, and this screen is read at arm's length before a run.
 */
@Composable
private fun ChaseSlider(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
    onSettled: () -> Unit,
    supporting: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink)
            Text(value, style = MaterialTheme.typography.titleSmall, color = Ember)
        }
        Slider(
            value = position,
            onValueChange = onChange,
            onValueChangeFinished = onSettled,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Ember,
                activeTrackColor = Ember,
                inactiveTrackColor = Rule,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = InkFaint)
        }
    }
}

/**
 * The chase, as the player set it up: one goal and how fast the pack moves.
 *
 * The two goals are exclusive on purpose. A clock and a distance both counting down gives a runner
 * two questions to answer at once and turns the one big number on the HUD into a number that could
 * mean either — which is the last thing anybody needs while being chased.
 */
data class Chase(
    val byDistance: Boolean = false,
    val distanceKm: Int = 3,
    val minutes: Int = 10,
    val zombieKph: Int = 8,
) {
    /** What is still owed, in the unit of whichever goal was chosen. */
    fun remaining(state: com.clashfit.core.model.ZombieRunState): String =
        if (byDistance) "%.1f".format(((distanceKm * 1000f - state.distanceM) / 1000f).coerceAtLeast(0f))
        else clock((minutes * 60f - state.elapsedSec).coerceAtLeast(0f))
}

/** Seconds as the shortest thing a glance can read: 45s, 12m, 1h20. */
internal fun clock(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 -> "${h}h${m.toString().padStart(2, '0')}"
        m > 0 -> "${m}m"
        else -> "${total}s"
    }
}

/** Converts a chase coordinate back to latitude and longitude for the map. */
private fun marker(
    origin: Pair<Double, Double>,
    eastM: Float,
    northM: Float,
    radiusM: Double,
    colour: androidx.compose.ui.graphics.Color,
    glyph: MapMarker.Glyph,
    closeness: Float = 0f,
): MapMarker {
    val mPerDegLat = 111_320.0
    val mPerDegLon = mPerDegLat * cos(origin.first * PI / 180.0)
    return MapMarker(
        lat = origin.first + northM / mPerDegLat,
        lon = origin.second + eastM / mPerDegLon,
        radiusM = radiusM,
        fill = colour.copy(alpha = 0.55f),
        outline = colour,
        filled = true,
        glyph = glyph,
        closeness = closeness,
    )
}

@Composable
private fun Briefing(
    graph: AppGraph,
    cfg: com.clashfit.core.config.CombatConfig.ModesSpec.ZombieRunSpec,
    locationOk: Boolean,
    onStart: (Chase) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle(initialValue = Prefs.Settings())
    // Seeded from the stored chase and re-seeded whenever it changes, so a drag reads smoothly and
    // the value that survives the screen is the one on disk.
    var byDistance by remember(settings.chaseWinByDistance) { mutableStateOf(settings.chaseWinByDistance) }
    var km by remember(settings.chaseDistanceKm) { mutableFloatStateOf(settings.chaseDistanceKm.toFloat()) }
    var mins by remember(settings.chaseMinutes) { mutableFloatStateOf(settings.chaseMinutes.toFloat()) }
    var kph by remember(settings.chaseZombieKph) { mutableFloatStateOf(settings.chaseZombieKph.toFloat()) }
    val chase = Chase(byDistance, km.roundToInt(), mins.roundToInt(), kph.roundToInt())
    fun persist() = scope.launch {
        graph.prefs.setChase(chase.byDistance, chase.distanceKm, chase.minutes, chase.zombieKph)
    }

    val context = LocalContext.current
    val gpsOn = remember {
        runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }.getOrDefault(true)
    }

    // Scrolls, because it no longer fits. Two explanation cards and a set of controls is taller
    // than a phone, and a Column that overflows does not clip politely — it compresses its
    // children until a slider is drawn through the buttons above it.
    Column(
        Modifier.fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState()),
    ) {
        SectionGap(8)
        Text("A real chase, on a real map", style = MaterialTheme.typography.headlineSmall, color = Ink)
        SectionGap(10)
        Text(
            "You get ${cfg.headStartSec} seconds. Then zombies spawn within ${cfg.spawnRadiusM} metres and " +
                "start closing. They move faster when your cadence drops, so stopping is what gets you " +
                "caught. There is nothing to pick up and nothing to fight with: one of them reaching " +
                "${cfg.captureRadiusM} metres ends the run, so the only answer is not being there.",
            style = MaterialTheme.typography.bodyMedium, color = InkMuted,
        )
        SectionGap(22)
        SectionTitle("Set the chase")
        SectionGap(10)
        AppCard(Modifier.fillMaxWidth(), padding = 16) {
            Column {
                // One goal, chosen first, because it decides which of the two sliders is even
                // worth showing.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "Last a time", true to "Cover a distance").forEachIndexed { i, (v, label) ->
                        SegmentedButton(
                            selected = byDistance == v,
                            onClick = { byDistance = v; persist() },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Ember, activeContentColor = Ground,
                                inactiveContainerColor = Panel, inactiveContentColor = InkMuted,
                                inactiveBorderColor = Rule, activeBorderColor = Ember,
                            ),
                        ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                    }
                }

                Spacer(Modifier.height(18.dp))
                if (byDistance) {
                    ChaseSlider(
                        label = "Distance to cover",
                        value = "${km.roundToInt()} km",
                        position = km, range = 1f..50f, steps = 48,
                        onChange = { km = it }, onSettled = { persist() },
                    )
                } else {
                    ChaseSlider(
                        label = "Time to last",
                        value = clock(mins * 60f),
                        position = mins, range = 1f..300f, steps = 298,
                        onChange = { mins = it }, onSettled = { persist() },
                    )
                }

                Spacer(Modifier.height(18.dp))
                ChaseSlider(
                    label = "Zombie speed",
                    value = "${kph.roundToInt()} km/h",
                    position = kph, range = 1f..10f, steps = 8,
                    onChange = { kph = it }, onSettled = { persist() },
                    supporting = "At a steady jog. They gain when you slow and drop back when you push.",
                )
            }
        }

        SectionGap(22)
        AppCard(Modifier.fillMaxWidth(), padding = 16) {
            Column {
                Text("This is the one mode that goes online", style = MaterialTheme.typography.titleSmall, color = Ember)
                Spacer(Modifier.height(6.dp))
                Text(
                    "It uses your location and downloads map tiles. The camera never opens, so no frame " +
                        "and no landmark exists to leave the phone, and your route is stored here like any " +
                        "other run. Every other mode still works with the radio off.",
                    style = MaterialTheme.typography.bodySmall, color = InkMuted,
                )
            }
        }
        SectionGap(14)
        // Said before the head start rather than discovered during it. Indoors the chase runs on
        // the step counter, which works and drifts; a player who knows that keeps moving and
        // reads the map for what it is. It is a warning, not a gate: the mode still starts.
        AppCard(Modifier.fillMaxWidth(), padding = 16) {
            Column {
                Text(
                    if (gpsOn) "No signal? It keeps going." else "Location services are off",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (gpsOn) Ink else Gassed,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (gpsOn) {
                        "Inside a building a satellite fix is wider than the building, so the chase " +
                            "switches to your step counter and compass. It keeps working — it just " +
                            "drifts, and standing still still gets you caught."
                    } else {
                        "Turn them on for a map that matches the street. Without them the chase runs " +
                            "entirely on your step counter, so keep moving and expect the map to drift."
                    },
                    style = MaterialTheme.typography.bodySmall, color = InkMuted,
                )
            }
        }

        SectionGap(18)
        if (locationOk) {
            PrimaryButton("Start the head start", Modifier.fillMaxWidth()) { onStart(chase) }
        } else {
            Text(
                "Zombie Run needs location. Grant it from the run tracker, then come back.",
                style = MaterialTheme.typography.bodySmall, color = InkFaint,
            )
        }
    }
}

@Composable
private fun ChaseHud(state: com.clashfit.core.model.ZombieRunState, goal: Chase, score: Int?) {
    when (state.phase) {
        ZombieRunPhase.HEAD_START -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("HEAD START", style = MaterialTheme.typography.labelLarge, color = Ember)
                Text(
                    "${state.headStartLeftSec.roundToInt()}",
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp), color = Ink,
                )
                Text("Move. They are already here.", style = MaterialTheme.typography.bodySmall, color = InkMuted)
            }
        }
        ZombieRunPhase.CHASE -> {
            val nearest = state.nearestZombieM
            val danger = nearest != null && nearest < 60f
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (nearest == null) "CLEAR" else "NEAREST",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (danger) Gassed else InkMuted,
                )
                Text(
                    nearest?.let { "${it.roundToInt()} m" } ?: "—",
                    style = MaterialTheme.typography.displayMedium.copy(fontSize = 72.sp),
                    color = if (danger) Gassed else Ink,
                )
            }
            SectionGap(8)
            StatStrip(
                listOf(
                    "${state.zombiesLeft}" to "Chasing",
                    "%.2f".format(state.distanceM / 1000f) to "Km run",
                    goal.remaining(state) to "To go",
                ),
            )
        }
        ZombieRunPhase.ESCAPED, ZombieRunPhase.CAUGHT -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.phase == ZombieRunPhase.ESCAPED) "ESCAPED" else "CAUGHT",
                    style = MaterialTheme.typography.displaySmall,
                    color = if (state.phase == ZombieRunPhase.ESCAPED) Success else Gassed,
                )
                Text(
                    "%.2f km covered, %s outrun".format(
                        state.distanceM / 1000f, clock(state.elapsedSec),
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = InkMuted,
                    textAlign = TextAlign.Center,
                )
                if (score != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "$score points",
                        style = MaterialTheme.typography.headlineSmall, color = Ember,
                    )
                    Text(
                        "Getting to the goal is most of it. The rest is ground covered.",
                        style = MaterialTheme.typography.labelSmall, color = InkFaint,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun Outcome(state: com.clashfit.core.model.ZombieRunState, onFinish: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.outcome == GameOutcome.RUNNING) {
            SecondaryButton("Give up", Modifier.fillMaxWidth(), onClick = onFinish)
        } else {
            PrimaryButton("Finish", Modifier.fillMaxWidth(), onClick = onFinish)
        }
    }
}
