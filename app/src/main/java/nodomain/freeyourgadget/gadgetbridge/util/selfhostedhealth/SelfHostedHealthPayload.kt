/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import nodomain.freeyourgadget.gadgetbridge.activities.charts.SleepAnalysis
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One `POST /api/health` body, already scoped to the day the server files it under. */
data class SelfHostedHealthDay(val date: String, val body: JSONObject)

data class SelfHostedHealthPayloadSet(
    val days: List<SelfHostedHealthDay>,
    /** Latest sleep session end contained in [days], epoch seconds; 0 when no session was included. */
    val sleepUploadedThrough: Long
)

/**
 * Turns raw [ActivitySample]s into the JSON bodies the self-hosted health server ingests.
 *
 * Pure by design (no Android, no database, no network) so the wire format can be unit tested,
 * which is the only genuinely unknown part of the direct-upload path.
 *
 * The server merges rather than overwrites: steps take the larger value, heart rate dedups on the
 * exact timestamp string, and overlapping sleep sessions keep the most complete span. Re-sending
 * data is therefore safe, and this builder leans on that. The sleep cursor still avoids needless
 * repeats; if a later fetch grows a night beyond the cursor, the corrected span is sent and replaces
 * the shorter server copy (see the settle rule in [build]).
 */
object SelfHostedHealthPayload {
    /**
     * Heart rate is downsampled into buckets of this size and averaged. The server keeps the last
     * 288 samples of a day and drops the rest, so 5 minutes is exactly the resolution that fills a
     * day without being truncated.
     */
    const val HEART_RATE_BUCKET_SECONDS = 300L

    /**
     * A sleep session is only uploaded once its end is this far behind the newest data we hold.
     *
     * The server now dedups sleep by overlapping span and keeps the most complete version, so a
     * night re-sent after it grew overwrites the shorter one instead of landing beside it under a
     * new key. This margin is therefore no longer what guards against the double count — it only
     * holds back a night that is *still in progress* (a fetch that stopped mid-session, where the
     * newest sample is itself the last sleep sample), so the server does not briefly show a
     * half-night. Ten minutes clears normal end-of-night stirring while letting a finished night
     * upload on the first fetch after waking, instead of waiting out the old half-hour.
     */
    const val SLEEP_SETTLE_SECONDS = 10L * 60L

    private const val STAGE_DEEP = "deep"
    private const val STAGE_LIGHT = "light"
    private const val STAGE_REM = "rem"
    private const val STAGE_AWAKE = "awake"

    private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    private data class Stage(val name: String, val start: Long, val end: Long)

    /**
     * @param samples samples for one device, any order.
     * @param zone zone used to decide which day a sample belongs to. Days are local midnight to
     *   local midnight, matching what the app itself shows.
     * @param sleepUploadedThrough epoch seconds of the newest sleep session already uploaded; only
     *   sessions ending after this are emitted.
     * @param nowEpochSecond wall clock, used together with the newest sample for the settle rule.
     */
    @JvmStatic
    fun build(
        samples: List<ActivitySample>,
        zone: ZoneId,
        sleepUploadedThrough: Long,
        nowEpochSecond: Long
    ): SelfHostedHealthPayloadSet {
        if (samples.isEmpty()) {
            return SelfHostedHealthPayloadSet(emptyList(), 0L)
        }
        val sorted = samples.sortedBy { it.timestamp }

        val stepsByDate = LinkedHashMap<LocalDate, Long>()
        // date -> bucket start -> [bpm sum, sample count]
        val heartRateByDate = LinkedHashMap<LocalDate, LinkedHashMap<Long, IntArray>>()

        for (sample in sorted) {
            val timestamp = sample.timestamp.toLong()
            val date = localDate(timestamp, zone)

            val steps = sample.steps
            if (steps > 0) {
                stepsByDate[date] = (stepsByDate[date] ?: 0L) + steps
            }

            // Same validity rule the Health Connect path uses: 255 is Gadgetbridge's documented
            // "bad measurement" sentinel and 0 means "not measured".
            val bpm = sample.heartRate
            if (bpm in 1..300 && bpm != 255) {
                val bucketStart = Math.floorDiv(timestamp, HEART_RATE_BUCKET_SECONDS) * HEART_RATE_BUCKET_SECONDS
                val accumulator = heartRateByDate
                    .getOrPut(date) { LinkedHashMap() }
                    .getOrPut(bucketStart) { IntArray(2) }
                accumulator[0] += bpm
                accumulator[1]++
            }
        }

        val sleepByDate = LinkedHashMap<LocalDate, MutableList<JSONObject>>()
        var newestSleepEnd = 0L
        val dataHorizon = minOf(nowEpochSecond, sorted.last().timestamp.toLong()) - SLEEP_SETTLE_SECONDS

        for (session in SleepAnalysis().calculateSleepSessions(sorted)) {
            val stages = buildStages(sorted, session)
            if (stages.isEmpty()) {
                continue
            }
            val start = stages.first().start
            val end = stages.last().end
            if (end > dataHorizon || end <= sleepUploadedThrough) {
                continue
            }
            // Match the app's own convention (DailyTotals, widget, charts): the reported duration is
            // time actually asleep, awake phases inside the session excluded.
            val asleepSeconds = stages.filter { it.name != STAGE_AWAKE }.sumOf { it.end - it.start }
            if (asleepSeconds <= 0) {
                continue
            }
            sleepByDate
                .getOrPut(localDate(end, zone)) { mutableListOf() }
                .add(sessionJson(start, end, asleepSeconds, stages, zone))
            if (end > newestSleepEnd) {
                newestSleepEnd = end
            }
        }

        val dates = sortedSetOf<LocalDate>().apply {
            addAll(stepsByDate.keys)
            addAll(heartRateByDate.keys)
            addAll(sleepByDate.keys)
        }

        val days = dates.mapNotNull { date ->
            val body = JSONObject()
            body.put("date", date.toString())

            stepsByDate[date]?.let { total ->
                body.put("steps", JSONObject().put("total", total))
            }
            heartRateByDate[date]?.let { buckets ->
                val array = JSONArray()
                for ((bucketStart, accumulator) in buckets.entries.sortedBy { it.key }) {
                    array.put(
                        JSONObject()
                            .put("timestamp", formatTimestamp(bucketStart, zone))
                            .put("value", Math.round(accumulator[0].toDouble() / accumulator[1]))
                    )
                }
                body.put("heart_rate", array)
            }
            sleepByDate[date]?.let { sessions ->
                body.put("sleep", JSONArray(sessions))
            }

            // "date" alone carries no data and would still rewrite the server's file.
            if (body.length() > 1) SelfHostedHealthDay(date.toString(), body) else null
        }

        return SelfHostedHealthPayloadSet(days, newestSleepEnd)
    }

    /**
     * Groups consecutive same-kind samples of one session into stages, mirroring the Health Connect
     * sleep syncer so both paths report identical stage boundaries. Samples that are not a sleep
     * kind end the current stage and are themselves skipped.
     */
    private fun buildStages(
        sortedSamples: List<ActivitySample>,
        session: SleepAnalysis.SleepSession
    ): List<Stage> {
        val sleepStart = session.sleepStart ?: return emptyList()
        val sleepEnd = session.sleepEnd ?: return emptyList()
        val fromTs = sleepStart.time / 1000L
        val toTs = sleepEnd.time / 1000L

        val sessionSamples = sortedSamples.filter { it.timestamp.toLong() in fromTs..toTs }
        if (sessionSamples.isEmpty()) {
            return emptyList()
        }

        val stages = mutableListOf<Stage>()
        var index = 0
        while (index < sessionSamples.size) {
            val stageName = stageName(sessionSamples[index].kind)
            if (stageName == null) {
                index++
                continue
            }
            val stageStart = sessionSamples[index].timestamp.toLong()
            var next = index + 1
            while (next < sessionSamples.size && stageName(sessionSamples[next].kind) == stageName) {
                next++
            }
            // A stage runs until the next differing sample; the session's last stage is closed one
            // second after its last sample, since nothing tells us how long it really lasted.
            val stageEnd = if (next < sessionSamples.size) {
                sessionSamples[next].timestamp.toLong()
            } else {
                sessionSamples.last().timestamp.toLong() + 1
            }
            if (stageEnd > stageStart) {
                stages.add(Stage(stageName, stageStart, stageEnd))
            }
            index = next
        }
        return stages
    }

    private fun sessionJson(
        start: Long,
        end: Long,
        asleepSeconds: Long,
        stages: List<Stage>,
        zone: ZoneId
    ): JSONObject {
        val stageArray = JSONArray()
        for (stage in stages) {
            stageArray.put(
                JSONObject()
                    .put("stage", stage.name)
                    .put("start_time", formatTimestamp(stage.start, zone))
                    .put("end_time", formatTimestamp(stage.end, zone))
                    .put("duration_seconds", stage.end - stage.start)
            )
        }
        return JSONObject()
            .put("session_start_time", formatTimestamp(start, zone))
            .put("session_end_time", formatTimestamp(end, zone))
            .put("duration_seconds", asleepSeconds)
            .put("stages", stageArray)
    }

    private fun stageName(kind: ActivityKind): String? = when (kind) {
        ActivityKind.DEEP_SLEEP -> STAGE_DEEP
        ActivityKind.LIGHT_SLEEP -> STAGE_LIGHT
        ActivityKind.REM_SLEEP -> STAGE_REM
        ActivityKind.AWAKE_SLEEP -> STAGE_AWAKE
        else -> null
    }

    private fun localDate(epochSecond: Long, zone: ZoneId): LocalDate =
        ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone).toLocalDate()

    /** ISO 8601 with an explicit offset: the server files data by calendar day and must not have to
     *  guess which zone a naive local time came from. */
    private fun formatTimestamp(epochSecond: Long, zone: ZoneId): String =
        TIMESTAMP_FORMAT.format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zone))
}
