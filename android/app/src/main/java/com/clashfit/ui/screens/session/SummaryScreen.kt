package com.clashfit.ui.screens.session

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clashfit.AppGraph
import com.clashfit.core.model.FatigueBand
import com.clashfit.data.RepEntity
import com.clashfit.data.SessionEntity
import com.clashfit.data.SetEntity
import com.clashfit.engine.core.AsymmetrySummary
import com.clashfit.engine.core.describeAsymmetry
import com.clashfit.meta.SessionReward
import com.clashfit.ui.components.AppCard
import com.clashfit.ui.components.BadgeTile
import com.clashfit.ui.components.Bar
import com.clashfit.ui.components.EmptyState
import com.clashfit.ui.components.Headline
import com.clashfit.ui.components.InnerDivider
import com.clashfit.ui.components.Kicker
import com.clashfit.ui.components.ListGroup
import com.clashfit.ui.components.PrimaryButton
import com.clashfit.ui.components.RuleRow
import com.clashfit.ui.components.SecondaryButton
import com.clashfit.ui.components.grouped
import com.clashfit.ui.components.SectionGap
import com.clashfit.ui.components.SectionTitle
import com.clashfit.ui.components.StatTile
import com.clashfit.ui.components.XpBar
import com.clashfit.ui.components.color
import com.clashfit.ui.screens.social.iconFor
import com.clashfit.ui.theme.Ember
import com.clashfit.ui.theme.Fresh
import com.clashfit.ui.theme.Gassed
import com.clashfit.ui.theme.Heavy
import com.clashfit.ui.theme.Ink
import com.clashfit.ui.theme.LocalUiHaptics
import com.clashfit.ui.theme.InkFaint
import com.clashfit.ui.theme.InkMuted
import com.clashfit.ui.theme.Panel
import com.clashfit.ui.theme.PanelLift
import com.clashfit.ui.theme.Rule
import com.clashfit.ui.theme.Success
import com.clashfit.ui.theme.Working
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.clashfit.ui.theme.LocalReduceMotion

/** The session as evidence: the fatigue curve, the form trend, the best and worst rep. */
data class SummaryData(
    val session: SessionEntity,
    val sets: List<SetEntity>,
    val reps: List<RepEntity>,
) {
    val formMeanPct: Int get() = if (reps.isEmpty()) 0 else (reps.map { it.formScore }.average() * 100).toInt()
    val best: RepEntity? get() = reps.maxByOrNull { it.formScore }
    val worst: RepEntity? get() = reps.minByOrNull { it.formScore }
    val cleanCount: Int get() = reps.count { it.verdict == "CLEAN" }
    val okCount: Int get() = reps.count { it.verdict == "OK" }
    val shallowCount: Int get() = reps.count { it.verdict == "SHALLOW" }
    val peakBand: FatigueBand get() = runCatching { FatigueBand.valueOf(session.peakBand) }.getOrDefault(FatigueBand.FRESH)
}

class SummaryViewModel(private val graph: AppGraph, private val sessionId: Long) : ViewModel() {
    private val _data = MutableStateFlow<SummaryData?>(null)
    val data: StateFlow<SummaryData?> = _data.asStateFlow()
    private val _exported = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = _exported.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val dao = graph.db.sessions()
            val s = dao.session(sessionId) ?: return@launch
            _data.value = SummaryData(s, dao.sets(sessionId), dao.reps(sessionId))
        }
    }

    /** CSV of every rep's raw telemetry, written to files/export with RFC 4180 escaping. */
    fun exportCsv(context: Context) {
        val d = _data.value ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, "export").apply { mkdirs() }
                val f = File(dir, "clashfit-${d.session.exerciseId}-${d.session.id}.csv")
                f.bufferedWriter().use { w ->
                    w.appendLine("repIndex,tStartMs,tEndMs,formScore,depth,rom,tempo,alignment,reason,verdict,concentricVelocity,damage,combo,fatigue,band,validFrameRatio,depthCm,heightCm,holdSec")
                    for (r in d.reps) {
                        val fields = listOf(
                            r.repIndex, r.tStartMs, r.tEndMs, r.formScore, r.depth, r.rom, r.tempo, r.alignment,
                            escapeCsv(r.reason), escapeCsv(r.verdict),
                            r.concentricVelocity, r.damage, r.comboAtRep, r.fatigueValue, escapeCsv(r.fatigueBand), r.validFrameRatio,
                            r.depthCm ?: "", r.heightCm ?: "", r.holdSec ?: ""
                        )
                        w.appendLine(fields.joinToString(","))
                    }
                }
                f
            }
            _exported.value = file.absolutePath
            // Share the file via intent, allowing user to email or message it
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.csv_provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ClashFit Session · ${d.session.exerciseId}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share CSV export"))
        }
    }

    /** Render a summary card to PNG and share it as an image. Falls back to text summary if capture fails. */
    fun shareAsImage(context: Context) {
        val d = _data.value ?: return
        viewModelScope.launch {
            try {
                val pngFile = withContext(Dispatchers.IO) {
                    val dir = File(context.filesDir, "export").apply { mkdirs() }
                    val f = File(dir, "clashfit-${d.session.exerciseId}-${d.session.id}.png")

                    // Render summary card to a bitmap
                    val bitmap = createSummaryCardBitmap(d)

                    // Write bitmap to PNG
                    FileOutputStream(f).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    bitmap.recycle()
                    f
                }

                // Share the PNG via intent
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.csv_provider", pngFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "ClashFit Session · ${d.session.exerciseId}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share result"))
            } catch (e: Exception) {
                // Fallback to text summary on any error (e.g., out of memory, canvas issues)
                shareTextSummary(context, d)
            }
        }
    }

    private fun createSummaryCardBitmap(d: SummaryData): Bitmap {
        val width = 800
        val height = 1000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        // Draw background
        val bgPaint = Paint().apply { color = 0xFF1A1A1A.toInt(); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Title
        var y = 60f
        drawTextCentered(canvas, "ClashFit", y, 48f, 0xFFFFFFFF.toInt())
        y += 80f

        // Exercise name
        drawTextCentered(canvas, d.session.exerciseId.replace('_', ' '), y, 36f, 0xFFBB86FC.toInt())
        y += 70f

        // Stats section
        val stats = listOf(
            "Reps" to "${d.session.totalReps.grouped()}",
            "Damage" to "${d.session.totalDamage.grouped()}",
            "Form" to "${d.formMeanPct}%",
        )

        for ((label, value) in stats) {
            y += 50f
            drawTextLeft(canvas, label, y, 24f, 0xFF999999.toInt())
            drawTextLeft(canvas, value, y + 35f, 32f, 0xFFFFFFFF.toInt())
        }

        return bitmap
    }

    private fun drawTextCentered(canvas: Canvas, text: String, y: Float, size: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val x = (canvas.width - bounds.width()) / 2f - bounds.left
        canvas.drawText(text, x, y, paint)
    }

    private fun drawTextLeft(canvas: Canvas, text: String, y: Float, size: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            textSize = size
            isAntiAlias = true
        }
        canvas.drawText(text, 60f, y, paint)
    }

    private fun shareTextSummary(context: Context, d: SummaryData) {
        val text = """
            ClashFit Session
            ${d.session.exerciseId.replace('_', ' ')}

            Reps: ${d.session.totalReps}
            Damage: ${d.session.totalDamage}
            Form: ${d.formMeanPct}%
        """.trimIndent()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "ClashFit Session · ${d.session.exerciseId}")
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share result"))
    }

    companion object {
        /**
         * RFC 4180 CSV escaping: wrap fields containing comma, newline, or quote in double quotes,
         * and escape internal quotes as double-double.
         */
        fun escapeCsv(value: String): String {
            return if (value.contains(",") || value.contains("\n") || value.contains("\"")) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
        }

        fun factory(graph: AppGraph, sessionId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SummaryViewModel(graph, sessionId) as T
        }
    }
}

@Composable
fun SummaryScreen(
    graph: AppGraph,
    sessionId: Long,
    onHome: () -> Unit,
    onAgain: (SessionEntity) -> Unit,
    onAskCoach: (Long) -> Unit = {},
) {
    val vm: SummaryViewModel = viewModel(key = "summary-$sessionId", factory = SummaryViewModel.factory(graph, sessionId))
    val data by vm.data.collectAsStateWithLifecycle()
    val exported by vm.exported.collectAsStateWithLifecycle()
    // Banking runs off the main thread after the fight ends, so the reward can land a moment
    // after this screen composes. Observe the store rather than reading it once, or the XP block
    // is missing on every fast phone.
    val rewards by graph.rewards.byId.collectAsStateWithLifecycle()
    // Frozen as the fight ended and tagged with this session's id, so it outlives the link and
    // can never label this summary with an older match's score.
    val versus by graph.playHub.result.collectAsStateWithLifecycle()
    val reward = rewards[sessionId]
    val d = data
    if (d == null) { EmptyState("Summary", "Loading the set…"); return }

    val reduceMotion = LocalReduceMotion.current

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
        // Outcome headline
        Kicker("Session · ${d.session.exerciseId.replace('_', ' ')} · ${d.session.mode.replace('_', ' ')}")
        // Every way a session can end has its own headline. The engine can finish for seven
        // reasons and this used to name three of them, so a family game you won, a timed mode that
        // ran out, and a set you walked away from all read "Set saved" — which told the player
        // nothing about the thing they had just done.
        // A versus fight is judged on the scoreboard, not on why the engine stopped. Every rep
        // race ends because the clock ran out, so reading the end reason called all of them
        // "Time" and never said who won.
        val decided = versus?.takeIf { it.sessionId == sessionId && it.hadOpponent }
        Headline(when {
            decided != null && decided.won -> "You won"
            decided != null && decided.lost -> "They won"
            decided != null -> "Dead even"
            else -> when (d.session.outcome) {
                "BOSS_DOWN" -> "Boss down"
                "GAME_WON" -> "You won"
                "GAME_LOST" -> "It caught you"
                "DEFEATED" -> "Defeated"
                "TIME" -> "Time"
                "WALKED_AWAY" -> "You left"
                else -> "Set saved"
            }
        })
        decided?.let { v ->
            SectionGap(14)
            AppCard(Modifier.fillMaxWidth(), padding = 18) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FinalScore(v.myName.ifBlank { "You" }, v.myScore, leading = v.myScore >= v.theirScore)
                    Text("–", style = MaterialTheme.typography.headlineMedium, color = InkFaint)
                    FinalScore(v.theirName.ifBlank { "Them" }, v.theirScore, leading = v.theirScore >= v.myScore)
                    Spacer(Modifier.weight(1f))
                    Text(v.unit.uppercase(), style = MaterialTheme.typography.labelSmall, color = InkFaint)
                }
            }
        }
        SectionGap(16)

        // Reward progression, revealed in order rather than printed all at once. Every number
        // here was already computed; showing them arriving one at a time is what turns a receipt
        // into the thing you did the set for.
        reward?.let { r ->
            val steps = 2 + r.lines.size + (if (r.leveledUp) 1 else 0) + r.newAchievements.size + r.newVouchers.size
            val shown by rememberReveal(steps, reduceMotion = reduceMotion)
            val total by rememberCountUp(r.xp, revealed = shown >= 1)

            // The XP lines are arithmetic; the level, the badges and the vouchers are the things
            // you did the set for. Each one lands in the hand as it lands on the screen — the same
            // motor that has been ticking every rep for the last three minutes, so the reward
            // arrives in the language the fight already established.
            val haptics = LocalUiHaptics.current
            val firstPrize = 1 + r.lines.size
            LaunchedEffect(shown) {
                if (shown > firstPrize && shown <= steps) haptics.reward()
            }

            AppCard(Modifier.fillMaxWidth(), padding = 18) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("$total XP earned", style = MaterialTheme.typography.headlineMedium, color = Ink)

                    ListGroup {
                        r.lines.forEachIndexed { i, line ->
                            RevealStep(visible = shown > i + 1) {
                                RuleRow(line.label, "${line.xp} XP")
                            }
                            if (i < r.lines.size - 1) InnerDivider()
                        }
                    }
                }
            }

            SectionGap(16)

            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    XpBar(r.after)

                    if (r.leveledUp) {
                        RevealStep(visible = shown > 1 + r.lines.size) {
                            Text(
                                "Level up · Reached ${r.after.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Success,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }

                    if (r.newAchievements.isNotEmpty()) {
                        SectionGap(8)
                        Text("New badges", style = MaterialTheme.typography.labelLarge, color = Ink)
                        val badgesFrom = 1 + r.lines.size + (if (r.leveledUp) 1 else 0)
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            r.newAchievements.forEachIndexed { i, achievement ->
                                BadgeSlam(
                                    visible = shown > badgesFrom + i,
                                    reduceMotion = reduceMotion,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    BadgeTile(achievement, unlocked = true, icon = iconFor(achievement))
                                }
                            }
                        }
                    }

                    // A voucher lands last and alone, because it is the rarest thing this screen
                    // can show and stacking it under the badges would bury it.
                    if (r.newVouchers.isNotEmpty()) {
                        SectionGap(8)
                        val voucherFrom = 1 + r.lines.size + (if (r.leveledUp) 1 else 0) + r.newAchievements.size
                        r.newVouchers.forEachIndexed { i, v ->
                            BadgeSlam(
                                visible = shown > voucherFrom + i,
                                reduceMotion = reduceMotion,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                AppCard(Modifier.fillMaxWidth(), padding = 14, container = PanelLift) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("A reward, from ${v.brand}", style = MaterialTheme.typography.labelMedium, color = Success)
                                        Text(v.offer, style = MaterialTheme.typography.titleMedium, color = Ink)
                                        Text(
                                            "${v.milestone} · find it under Rewards",
                                            style = MaterialTheme.typography.labelSmall, color = InkFaint,
                                        )
                                    }
                                }
                            }
                            SectionGap(8)
                        }
                    }
                }
            }

            SectionGap(16)

            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(r.weekly.challenge.title, style = MaterialTheme.typography.labelMedium, color = InkMuted)
                        Text(if (r.weekly.done) "Done" else "${r.weekly.value} / ${r.weekly.challenge.target}", style = MaterialTheme.typography.titleSmall, color = if (r.weekly.done) Success else Ink)
                    }
                    Bar(r.weekly.fraction, color = if (r.weekly.done) Success else Ember)
                }
            }

            SectionGap()
        }

        // Two by two, not four across.
        //
        // Four tiles on a phone gave each about eighty pixels, and the values that go in them are
        // not short: a four-figure damage total broke as "128 / 7" and WORKING came out as three
        // stacked syllables. Half as many per row is twice the width, and it holds at 1.5x text
        // as well, which four never could.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${d.session.totalReps.grouped()}", "reps", Modifier.weight(1f))
            StatTile("${d.session.totalDamage.grouped()}", "damage", Modifier.weight(1f))
        }
        SectionGap(10)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("${d.formMeanPct}%", "form", Modifier.weight(1f))
            StatTile(d.peakBand.label, "peak", Modifier.weight(1f), color = d.peakBand.color())
        }
        SectionGap()

        // Fatigue curve
        SectionTitle("Fatigue curve")
        SectionGap(10)
        AppCard(Modifier.fillMaxWidth(), padding = 12) { FatigueCurve(d.reps, Modifier.fillMaxWidth().height(200.dp)) }
        SectionGap()

        // Form sparkline
        SectionTitle("Form per rep")
        SectionGap(10)
        AppCard(Modifier.fillMaxWidth(), padding = 12) { FormSparkline(d.reps, Modifier.fillMaxWidth().height(90.dp)) }
        Text("${d.cleanCount} clean · ${d.okCount} good · ${d.shallowCount} shallow", style = MaterialTheme.typography.labelMedium, color = InkMuted, modifier = Modifier.padding(top = 8.dp))
        SectionGap()

        // Best and worst reps in a ListGroup
        if (d.best != null || d.worst != null) {
            ListGroup {
                d.best?.let { rep ->
                    RuleRow("Best rep · #${rep.repIndex}", "${(rep.formScore * 100).toInt()}%${rep.depthCm?.let { c -> " · %.0f cm".format(c) } ?: ""}")
                    if (d.worst != null) InnerDivider()
                }
                d.worst?.let { rep ->
                    RuleRow("Worst rep · #${rep.repIndex}", "${(rep.formScore * 100).toInt()}% · ${rep.reason}")
                }
            }
            SectionGap()
        }

        // Per-set rows in ListGroups with coach lines and asymmetry info
        for ((setIdx, set) in d.sets.withIndex()) {
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    RuleRow("Set ${set.setIndex} · ${set.reps} reps", "${(set.formMean * 100).toInt()}% · ${set.fatigueBandEnd}")

                    set.coachLine?.let { line ->
                        Text("\"$line\"", style = MaterialTheme.typography.bodyMedium, color = InkMuted, modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                    }

                    if (set.asymmetryPct != null) {
                        val summary = AsymmetrySummary(
                            usable = 0,
                            enough = true,
                            meanLsi = 100f - set.asymmetryPct,
                            deficitPct = set.asymmetryPct.toFloat(),
                            weakerSide = set.weakerSide,
                            consistency = 1f,
                            consistent = true,
                        )
                        Text(describeAsymmetry(summary), style = MaterialTheme.typography.bodyMedium, color = InkMuted, modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                    }
                }
            }
            if (setIdx < d.sets.size - 1) SectionGap(10)
        }
        SectionGap()

        // Action buttons
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // The strongest thing the coach can do is answer about the set you are looking at, so
        // the way in is here, while the numbers are still on the screen.
        SecondaryButton("Ask the coach", Modifier.fillMaxWidth()) { onAskCoach(d.session.id) }
        SectionGap(10)
        PrimaryButton("Fight again", Modifier.fillMaxWidth()) { onAgain(d.session) }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Share result", Modifier.fillMaxWidth()) { vm.shareAsImage(ctx) }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(if (exported == null) "Export CSV" else "Exported", Modifier.fillMaxWidth()) { vm.exportCsv(ctx) }
        exported?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = InkFaint, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Home", Modifier.fillMaxWidth(), onClick = onHome)
        SectionGap()
    }
}

/** Fatigue rising across the set, over the four band regions. The one image no other app has. */
@Composable
fun FatigueCurve(reps: List<RepEntity>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val bands = listOf(0f to Fresh, 0.15f to Working, 0.30f to Heavy, 0.50f to Gassed, 0.65f to Gassed)
        for (i in 0 until bands.size - 1) {
            val y0 = h * (bands[i].first / 0.65f); val y1 = h * (bands[i + 1].first / 0.65f)
            drawRect(bands[i].second.copy(alpha = 0.14f), topLeft = Offset(0f, y0), size = Size(w, y1 - y0))
        }
        for (t in listOf(0.15f, 0.30f, 0.50f)) {
            val y = h * (t / 0.65f)
            drawLine(Rule, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (reps.size < 2) return@Canvas
        val path = Path()
        reps.forEachIndexed { i, r ->
            val x = w * i / (reps.size - 1).toFloat()
            val y = h * (r.fatigueValue.coerceIn(0f, 0.65f) / 0.65f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Ember, style = Stroke(width = 5f))
        val last = reps.last()
        drawCircle(Ember, radius = 7f, center = Offset(w, h * (last.fatigueValue.coerceIn(0f, 0.65f) / 0.65f)))
    }
}

@Composable
fun FormSparkline(reps: List<RepEntity>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawLine(Rule, Offset(0f, h * 0.2f), Offset(w, h * 0.2f), strokeWidth = 1f)
        drawLine(Rule, Offset(0f, h * 0.45f), Offset(w, h * 0.45f), strokeWidth = 1f)
        if (reps.isEmpty()) return@Canvas
        val bw = w / reps.size
        reps.forEachIndexed { i, r ->
            val colour = when (r.verdict) { "CLEAN" -> Fresh; "OK" -> Working; else -> Heavy }
            val bh = h * r.formScore.coerceIn(0.04f, 1f)
            drawRect(colour, topLeft = Offset(i * bw + 1f, h - bh), size = Size((bw - 2f).coerceAtLeast(1f), bh))
        }
    }
}

/** One side of the final score. The winner is in ember; a draw lights both. */
@Composable
private fun FinalScore(name: String, score: Int, leading: Boolean) {
    Column {
        Text("$score", style = MaterialTheme.typography.headlineLarge, color = if (leading) Ember else InkMuted)
        Text(name.uppercase(), style = MaterialTheme.typography.labelSmall, color = InkFaint)
    }
}
