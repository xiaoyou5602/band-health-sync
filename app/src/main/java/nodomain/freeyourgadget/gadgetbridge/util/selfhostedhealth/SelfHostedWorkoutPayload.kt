package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import org.json.JSONArray
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Allowlisted, non-location projection of existing Huawei/BaseActivitySummary records.
 * No raw entities, details, addresses or free-form workout names reach serialization.
 */
data class WorkoutSummaryInput(
    val localId: Long, val rawType: Int, val activityKind: Int,
    val startSeconds: Long, val endSeconds: Long, val activeSeconds: Long,
    val totalSeconds: Long?, val distanceMeters: Double?, val activeCalories: Double?,
    val averageHeartRate: Int?, val minHeartRate: Int?, val maxHeartRate: Int?
)

object SelfHostedWorkoutPayload {
    private val LOG = LoggerFactory.getLogger(SelfHostedWorkoutPayload::class.java)

    @JvmStatic
    fun attach(base: SelfHostedHealthPayloadSet, rows: List<WorkoutSummaryInput>, zone: ZoneId, captured: Long): SelfHostedHealthPayloadSet {
        if (rows.isEmpty()) return base
        // Validate before grouping: even an unrepresentable timestamp is only a bad record.
        val grouped = sortedMapOf<String, MutableList<JSONObject>>()
        val identities = HashSet<String>()
        for (w in rows.sortedWith(compareBy({ it.startSeconds }, { it.localId }))) {
            val record = try {
                val body = serialize(w, zone, captured)
                val date = Instant.ofEpochSecond(w.startSeconds).atZone(zone).toLocalDate().toString()
                date to body
            } catch (e: Exception) {
                // Do not log exception messages or values; they may include private summaries.
                LOG.warn("Skipping malformed workout summary ({})", e.javaClass.simpleName)
                continue
            }
            val (date, body) = record
            val id = body.getString("id")
            // First valid record wins; a malformed record never reserves an identity.
            if (!identities.add(id)) {
                LOG.warn("Skipping duplicate workout summary")
                continue
            }
            val workouts = grouped.getOrPut(date) { mutableListOf() }
            if (workouts.size >= 128) {
                LOG.warn("Skipping workout summary exceeding the per-day limit")
                continue
            }
            workouts.add(body)
        }
        if (grouped.isEmpty()) return base

        // Never mutate legacy JSON; a workout-subsystem failure can return the exact original.
        val days = base.days.associateBy { it.date }.toMutableMap()
        for ((date, workouts) in grouped) {
            val body = days[date]?.body?.let { JSONObject(it.toString()) } ?: JSONObject().put("date", date)
            body.put("workouts", JSONArray(workouts))
            days[date] = SelfHostedHealthDay(date, body)
        }
        return SelfHostedHealthPayloadSet(days.toSortedMap().values.toList(), base.sleepUploadedThrough)
    }

    private fun serialize(w: WorkoutSummaryInput, zone: ZoneId, captured: Long): JSONObject {
        val id = identity(w)
        val elapsed = Math.subtractExact(w.endSeconds, w.startSeconds)
        require(elapsed in 1..7 * 86400L && captured >= w.endSeconds && w.activeSeconds in 0..elapsed) { "workout_summary_time" }
        require(w.totalSeconds == null || w.totalSeconds in w.activeSeconds..elapsed) { "workout_summary_total" }
        require(w.distanceMeters == null || (w.distanceMeters.isFinite() && w.distanceMeters in 0.0..10000000.0))
        require(w.activeCalories == null || (w.activeCalories.isFinite() && w.activeCalories in 0.0..10000000.0))
        for (hr in listOf(w.averageHeartRate, w.minHeartRate, w.maxHeartRate)) require(hr == null || (hr in 1..300 && hr != 255))
        require(w.minHeartRate == null || w.maxHeartRate == null || w.minHeartRate <= w.maxHeartRate)
        require(w.averageHeartRate == null || ((w.minHeartRate == null || w.averageHeartRate >= w.minHeartRate) &&
            (w.maxHeartRate == null || w.averageHeartRate <= w.maxHeartRate)))
        return JSONObject().put("id", id).put("raw_type", w.rawType).put("activity_kind", w.activityKind)
            .put("start_time", timestamp(w.startSeconds, zone)).put("end_time", timestamp(w.endSeconds, zone))
            .put("timezone", zone.id).put("captured_at", timestamp(captured, zone))
            .put("active_seconds", w.activeSeconds)
            .put("total_seconds", w.totalSeconds ?: JSONObject.NULL)
            .put("distance_meters", w.distanceMeters ?: JSONObject.NULL)
            .put("active_calories", w.activeCalories ?: JSONObject.NULL)
            .put("average_heart_rate", w.averageHeartRate ?: JSONObject.NULL)
            .put("min_heart_rate", w.minHeartRate ?: JSONObject.NULL)
            .put("max_heart_rate", w.maxHeartRate ?: JSONObject.NULL)
    }

    @JvmStatic
    fun identity(w: WorkoutSummaryInput): String {
        require(w.localId in 1..999999999999999L && w.rawType in 0..255 && w.activityKind >= 0)
        return "gbw1:${w.localId}:${w.startSeconds}:${w.rawType}:${w.activityKind}"
    }

    private fun timestamp(seconds: Long, zone: ZoneId) =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochSecond(seconds).atZone(zone))
}
