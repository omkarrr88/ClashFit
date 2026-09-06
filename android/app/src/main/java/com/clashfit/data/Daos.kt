package com.clashfit.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1") fun observe(): Flow<ProfileEntity?>
    @Query("SELECT * FROM profile WHERE id = 1") suspend fun get(): ProfileEntity?
    @Upsert suspend fun upsert(p: ProfileEntity)
}

@Dao
interface CalibrationDao {
    @Query("SELECT * FROM calibration WHERE exerciseId = :exerciseId") suspend fun get(exerciseId: String): CalibrationEntity?
    @Upsert suspend fun upsert(c: CalibrationEntity)
    @Query("DELETE FROM calibration") suspend fun clear()
}

@Dao
interface SessionDao {
    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Insert suspend fun insertSet(s: SetEntity): Long
    @Insert suspend fun insertReps(reps: List<RepEntity>)
    @Query("UPDATE sessions SET endedAtMs = :endedAtMs, outcome = :outcome, totalDamage = :damage, totalReps = :reps, formMean = :formMean, peakFatigue = :peakFatigue, peakBand = :peakBand WHERE id = :id")
    suspend fun finish(id: Long, endedAtMs: Long, outcome: String?, damage: Int, reps: Int, formMean: Float, peakFatigue: Float, peakBand: String)

    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: Long): SessionEntity?
    @Query("SELECT * FROM sets WHERE sessionId = :sessionId ORDER BY setIndex") suspend fun sets(sessionId: Long): List<SetEntity>
    @Query("SELECT * FROM reps WHERE sessionId = :sessionId ORDER BY tStartMs") suspend fun reps(sessionId: Long): List<RepEntity>

    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC LIMIT :limit") fun recent(limit: Int = 50): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE exerciseId = :exerciseId AND endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun byExercise(exerciseId: String, limit: Int = 20): List<SessionEntity>
    @Query("SELECT * FROM sessions WHERE mode = :mode AND endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :limit")
    suspend fun byMode(mode: String, limit: Int = 50): List<SessionEntity>
    @Query("SELECT COUNT(*) FROM sessions WHERE endedAtMs IS NOT NULL") fun count(): Flow<Int>

    /** Damage dealt since a moment, for the weekly leaderboard. */
    @Query("SELECT COALESCE(SUM(totalDamage), 0) FROM sessions WHERE startedAtMs >= :sinceMs AND endedAtMs IS NOT NULL")
    suspend fun damageSince(sinceMs: Long): Long

    /** Reps the referee graded CLEAN since a moment, for the weekly leaderboard. */
    @Query("SELECT COUNT(*) FROM reps WHERE verdict = 'CLEAN' AND sessionId IN (SELECT id FROM sessions WHERE startedAtMs >= :sinceMs AND endedAtMs IS NOT NULL)")
    suspend fun cleanRepsSince(sinceMs: Long): Long
    @Query("SELECT * FROM sessions WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT 1") suspend fun last(): SessionEntity?

    /** Older sessions keep their aggregates but drop per-rep telemetry, so a phone never fills up. */
    // id DESC breaks ties on startedAtMs. Two sessions can share a millisecond, and without a
    // second key SQLite is free to pick either — so which one kept its per-rep telemetry was
    // decided by nothing in particular.
    @Query("DELETE FROM reps WHERE sessionId NOT IN (SELECT id FROM sessions ORDER BY startedAtMs DESC, id DESC LIMIT :keepFull)")
    suspend fun compact(keepFull: Int = 20)

    @Transaction
    suspend fun recordSet(set: SetEntity, reps: List<RepEntity>): Long {
        val setId = insertSet(set)
        insertReps(reps.map { it.copy(setId = setId) })
        return setId
    }
}

@Dao
interface StreakDao {
    @Query("SELECT * FROM streak WHERE id = 1") fun observe(): Flow<StreakEntity?>
    @Query("SELECT * FROM streak WHERE id = 1") suspend fun get(): StreakEntity?
    @Upsert suspend fun upsert(s: StreakEntity)
}

@Dao
interface BestDao {
    @Query("SELECT * FROM bests WHERE exerciseId = :exerciseId") suspend fun get(exerciseId: String): PersonalBestEntity?
    @Query("SELECT * FROM bests") fun all(): Flow<List<PersonalBestEntity>>
    @Upsert suspend fun upsert(b: PersonalBestEntity)
}

@Dao
interface LadderDao {
    @Query("SELECT * FROM ladders WHERE ladderId = :ladderId") suspend fun get(ladderId: String): LadderEntity?
    @Query("SELECT * FROM ladders") fun all(): Flow<List<LadderEntity>>
    @Upsert suspend fun upsert(l: LadderEntity)
}

@Dao
interface WorkoutDao {
    @Insert suspend fun startWorkout(w: WorkoutEntity): Long

    @Query("UPDATE workouts SET endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun finishWorkout(id: Long, endedAtMs: Long)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Insert suspend fun addSet(set: WorkoutSetEntity): Long

    @Query("SELECT COUNT(*) FROM workout_sets WHERE workoutId = :workoutId")
    suspend fun setCount(workoutId: Long): Int

    /** Sets of ONE exercise inside this workout, which is what the set number counts. */
    @Query("SELECT COUNT(*) FROM workout_sets WHERE workoutId = :workoutId AND exerciseId = :exerciseId")
    suspend fun setCountOf(workoutId: Long, exerciseId: String): Int

    @Query("SELECT * FROM workout_sets WHERE workoutId = :workoutId ORDER BY id")
    fun setsOf(workoutId: Long): Flow<List<WorkoutSetEntity>>

    @Query("SELECT * FROM workouts WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :limit")
    fun recentWorkouts(limit: Int = 30): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workout_sets WHERE workoutId IN (:ids) ORDER BY id")
    suspend fun setsForWorkouts(ids: List<Long>): List<WorkoutSetEntity>

    /**
     * The last set of this exercise, from any previous workout.
     *
     * This is what fills the weight and rep placeholders. Seeing what you did last time is the
     * whole mechanism by which a log makes anybody stronger: the number to beat has to be on the
     * screen at the moment you are deciding what to load, not two taps away in a history page.
     */
    @Query(
        "SELECT * FROM workout_sets WHERE exerciseId = :exerciseId AND workoutId != :exceptWorkoutId " +
            "ORDER BY endedAtMs DESC LIMIT 1",
    )
    suspend fun lastSetOf(exerciseId: String, exceptWorkoutId: Long): WorkoutSetEntity?

    /**
     * The same set number, from the last workout that reached it.
     *
     * A log that only remembers "your last set" tells you your fifth set when you are about to do
     * your first, and a fifth set is always the worst one — so the number on screen is a number you
     * would beat by accident. Sets are compared like with like: set one against last week's set
     * one, set three against last week's set three.
     *
     * Deliberately the most recent rather than the best. A personal record has its own line; this
     * one answers "what did I actually do last time", and an all-time best from four months ago is
     * a different and much less useful question.
     */
    @Query(
        "SELECT * FROM workout_sets WHERE exerciseId = :exerciseId AND setIndex = :setIndex " +
            "AND workoutId != :exceptWorkoutId ORDER BY endedAtMs DESC LIMIT 1",
    )
    suspend fun lastSetAt(exerciseId: String, setIndex: Int, exceptWorkoutId: Long): WorkoutSetEntity?

    /** The heaviest set ever recorded for this exercise, for the personal record line. */
    @Query("SELECT MAX(weightKg) FROM workout_sets WHERE exerciseId = :exerciseId")
    suspend fun heaviestKg(exerciseId: String): Float?

    /** The most reps ever done at or above a weight, so a record means something specific. */
    @Query("SELECT MAX(reps) FROM workout_sets WHERE exerciseId = :exerciseId AND weightKg >= :atLeastKg")
    suspend fun bestRepsAt(exerciseId: String, atLeastKg: Float): Int?

    @Query("SELECT COUNT(*) FROM workout_sets")
    fun totalSets(): Flow<Int>
}

@Dao
interface BreathingDao {
    @Insert suspend fun insert(session: BreathingEntity): Long

    @Query("SELECT * FROM breathing_sessions ORDER BY startedAtMs DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<BreathingEntity>>

    @Query("SELECT COUNT(*) FROM breathing_sessions")
    fun sessionCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM breathing_sessions")
    fun totalSeconds(): Flow<Int>

    /** Distinct days that carry at least one session, newest first, as yyyy-MM-dd in local time. */
    @Query(
        "SELECT DISTINCT date(startedAtMs / 1000, 'unixepoch', 'localtime') AS day " +
            "FROM breathing_sessions ORDER BY day DESC",
    )
    fun practiceDays(): Flow<List<String>>

    @Query("SELECT COALESCE(SUM(seconds), 0) FROM breathing_sessions WHERE startedAtMs >= :sinceMs")
    suspend fun secondsSince(sinceMs: Long): Int

    @Query("DELETE FROM breathing_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RunDao {
    @Insert suspend fun insertRun(r: RunEntity): Long
    @Insert suspend fun insertPoints(points: List<RunPointEntity>)
    @Query(
        "UPDATE runs SET endedAtMs = :endedAtMs, distanceM = :distanceM, movingMs = :movingMs, " +
            "avgPaceSecPerKm = :pace, cadenceSpm = :cadence, elevationGainM = :elev, splitsJson = :splits, " +
            "steps = :steps, fastestKmSec = :fastestKmSec WHERE id = :id",
    )
    suspend fun finish(
        id: Long, endedAtMs: Long, distanceM: Float, movingMs: Long, pace: Float,
        cadence: Int, elev: Float, splits: String, steps: Int, fastestKmSec: Float?,
    )
    @Query("SELECT * FROM runs WHERE id = :id") suspend fun run(id: Long): RunEntity?

    /**
     * Throws an activity away, points and all.
     *
     * Used for the one that recorded nothing: started, then finished a second later by a thumb
     * that meant to press something else. Deleting is right rather than leaving it unfinished,
     * because an unfinished row is invisible to the feed but still sits in the table forever.
     */
    @Query("DELETE FROM run_points WHERE runId = :id") suspend fun deletePoints(id: Long)
    @Query("DELETE FROM runs WHERE id = :id") suspend fun deleteRun(id: Long)
    @Query("SELECT * FROM run_points WHERE runId = :runId ORDER BY tMs") suspend fun points(runId: Long): List<RunPointEntity>

    /**
     * Every eighth point of several activities at once, for the list thumbnails.
     *
     * A feed of ten runs is tens of thousands of rows at full resolution, to draw shapes 120 dp
     * wide where most of those points land on the same pixel. Sampling in SQL rather than after
     * the fact is the difference between a scroll that stutters and one that does not; the shape
     * survives because the simplifier throws away most of the rest anyway.
     */
    @Query("SELECT * FROM run_points WHERE runId IN (:runIds) AND id % 8 = 0 ORDER BY runId, tMs")
    suspend fun sampledPoints(runIds: List<Long>): List<RunPointEntity>
    @Query("SELECT * FROM runs WHERE endedAtMs IS NOT NULL ORDER BY startedAtMs DESC LIMIT :limit") fun recent(limit: Int = 50): Flow<List<RunEntity>>
    @Query("SELECT * FROM runs WHERE endedAtMs IS NOT NULL AND kind = :kind ORDER BY startedAtMs DESC LIMIT :limit")
    fun recentOfKind(kind: String, limit: Int = 50): Flow<List<RunEntity>>
    @Query("SELECT COALESCE(SUM(distanceM), 0) FROM runs WHERE endedAtMs IS NOT NULL AND startedAtMs >= :sinceMs") suspend fun distanceSince(sinceMs: Long): Float
    @Query("SELECT COALESCE(SUM(steps), 0) FROM runs WHERE endedAtMs IS NOT NULL AND startedAtMs >= :sinceMs") suspend fun stepsSince(sinceMs: Long): Int
    @Query("SELECT COALESCE(SUM(movingMs), 0) FROM runs WHERE endedAtMs IS NOT NULL AND startedAtMs >= :sinceMs") suspend fun movingMsSince(sinceMs: Long): Long
    @Query("SELECT COUNT(*) FROM runs WHERE endedAtMs IS NOT NULL AND startedAtMs >= :sinceMs") suspend fun countSince(sinceMs: Long): Int

    // Personal bests. Every one excludes the activity being judged, so a run cannot beat itself —
    // it is already in the table by the time the summary screen asks.
    @Query("SELECT MAX(distanceM) FROM runs WHERE endedAtMs IS NOT NULL AND id != :exceptId")
    suspend fun bestDistanceM(exceptId: Long): Float?
    @Query("SELECT MAX(elevationGainM) FROM runs WHERE endedAtMs IS NOT NULL AND id != :exceptId")
    suspend fun bestClimbM(exceptId: Long): Float?
    @Query("SELECT MIN(fastestKmSec) FROM runs WHERE endedAtMs IS NOT NULL AND fastestKmSec IS NOT NULL AND id != :exceptId")
    suspend fun bestFastestKmSec(exceptId: Long): Float?
    @Query("SELECT MAX(steps) FROM runs WHERE endedAtMs IS NOT NULL AND id != :exceptId")
    suspend fun bestSteps(exceptId: Long): Int?

    // Lifetime outdoor totals, read from the activities themselves rather than from a counter
    // column, so what the badges are awarded on is always what the history shows.
    @Query("SELECT COALESCE(SUM(distanceM), 0) FROM runs WHERE endedAtMs IS NOT NULL")
    suspend fun totalDistanceM(): Float
    @Query("SELECT COUNT(*) FROM runs WHERE endedAtMs IS NOT NULL")
    suspend fun totalCount(): Int

    @Query("UPDATE runs SET score = :score WHERE id = :id")
    suspend fun setScore(id: Long, score: Int)

    /** This week's Zombie Run points, for the chase leaderboard. */
    @Query(
        "SELECT COALESCE(SUM(score), 0) FROM runs WHERE endedAtMs IS NOT NULL " +
            "AND kind = 'ZOMBIE_RUN' AND startedAtMs >= :sinceMs",
    )
    suspend fun chaseScoreSince(sinceMs: Long): Long
}

@Dao
interface AlarmDao {
    @Query("SELECT * FROM alarms ORDER BY hour, minute") fun all(): Flow<List<AlarmEntity>>
    @Query("SELECT * FROM alarms WHERE enabled = 1") suspend fun enabled(): List<AlarmEntity>
    @Query("SELECT * FROM alarms WHERE id = :id") suspend fun get(id: Long): AlarmEntity?
    @Upsert suspend fun upsert(a: AlarmEntity): Long
    @Delete suspend fun delete(a: AlarmEntity)
}

@Dao
interface GhostDao {
    @Query("SELECT * FROM ghosts ORDER BY createdAtMs DESC") fun all(): Flow<List<GhostEntity>>
    @Query("SELECT * FROM ghosts WHERE exerciseId = :exerciseId ORDER BY createdAtMs DESC") suspend fun forExercise(exerciseId: String): List<GhostEntity>
    @Query("SELECT * FROM ghosts WHERE id = :id") suspend fun get(id: String): GhostEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(g: GhostEntity)
    @Delete suspend fun delete(g: GhostEntity)
}

@Dao
interface PostureDao {
    @Insert suspend fun insert(p: PostureSampleEntity)
    @Query("SELECT * FROM posture WHERE tMs >= :sinceMs ORDER BY tMs DESC") fun since(sinceMs: Long): Flow<List<PostureSampleEntity>>
    @Query("SELECT * FROM posture ORDER BY tMs DESC LIMIT 1") suspend fun latest(): PostureSampleEntity?
}

@Dao
interface MetaDao {
    @Query("SELECT * FROM meta WHERE id = 1") fun observe(): Flow<MetaEntity?>
    @Query("SELECT * FROM meta WHERE id = 1") suspend fun get(): MetaEntity?
    @Upsert suspend fun upsert(m: MetaEntity)
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievements ORDER BY unlockedAtMs DESC") fun all(): Flow<List<AchievementEntity>>
    @Query("SELECT * FROM achievements WHERE id = :id") suspend fun get(id: String): AchievementEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(a: AchievementEntity)
    @Query("SELECT id FROM achievements") suspend fun allIds(): List<String>
}

@Dao
interface VoucherDao {
    @Query("SELECT * FROM vouchers ORDER BY earnedAtMs DESC") fun all(): Flow<List<VoucherEntity>>
    @Query("SELECT id FROM vouchers") suspend fun allIds(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(v: VoucherEntity)
    @Query("UPDATE vouchers SET redeemedAtMs = :atMs WHERE id = :id") suspend fun markRedeemed(id: String, atMs: Long)
    @Query("UPDATE vouchers SET redeemedAtMs = NULL WHERE id = :id") suspend fun markUnredeemed(id: String)
}

@Dao
interface WeeklyDao {
    @Query("SELECT * FROM weekly WHERE weekKey = :weekKey") fun observe(weekKey: String): Flow<WeeklyEntity?>
    @Query("SELECT * FROM weekly WHERE weekKey = :weekKey") suspend fun get(weekKey: String): WeeklyEntity?
    @Upsert suspend fun upsert(w: WeeklyEntity)
}

@Dao
interface XpLedgerDao {
    @Query("SELECT * FROM xp_ledger WHERE sessionId = :sessionId") suspend fun get(sessionId: Long): XpLedgerEntity?
    @Insert suspend fun insert(l: XpLedgerEntity)
}
