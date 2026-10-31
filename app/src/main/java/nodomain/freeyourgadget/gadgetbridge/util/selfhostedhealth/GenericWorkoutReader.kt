package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import android.database.Cursor
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummarySimpleEntry
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.*
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser
import org.slf4j.LoggerFactory
import java.util.Date

/** Common summary projection, independent of brand and track capability. Never loads track paths. */
object GenericWorkoutReader {
    private val LOG = LoggerFactory.getLogger(GenericWorkoutReader::class.java)

    // Keep the existing gbw1 wire contract and Huawei IDs. The upper half of its 15-digit ID
    // space is reserved for BaseActivitySummary IDs (global across devices in this database).
    private const val ID_OFFSET = 500_000_000_000_000L

    const val QUERY = """
        SELECT _id,ACTIVITY_KIND,START_TIME,END_TIME,SUMMARY_DATA,RAW_SUMMARY_DATA,DEVICE_ID,USER_ID
        FROM BASE_ACTIVITY_SUMMARY
        WHERE DEVICE_ID=? AND START_TIME>=? AND START_TIME<? AND END_TIME<=?
        ORDER BY START_TIME,_id
    """

    fun read(device: GBDevice, fromSeconds: Long, toSeconds: Long): List<WorkoutSummaryInput> {
        val parser = device.deviceCoordinator.getActivitySummaryParser(device, GBApplication.getContext())
        return GBApplication.acquireDbReadOnly().use { db ->
            val selector = DBHelper.getDevice(device, db.daoSession) ?: return@use emptyList()
            db.database.rawQuery(QUERY, arrayOf(selector.id.toString(), (fromSeconds * 1000).toString(),
                (toSeconds * 1000).toString(), (toSeconds * 1000).toString())).use {
                readRows(it, parser, toSeconds)
            }
        }
    }

    @JvmStatic
    fun readRows(cursor: Cursor, parser: ActivitySummaryParser?, captured: Long): List<WorkoutSummaryInput> {
        val rows = mutableListOf<WorkoutSummaryInput>()
        while (cursor.moveToNext()) {
            // Materialize outside the per-record guard so a broken cursor fails the subsystem.
            val summary = BaseActivitySummary().apply {
                id = if (cursor.isNull(0)) null else cursor.getLong(0)
                activityKind = cursor.getInt(1)
                if (!cursor.isNull(2)) startTime = Date(cursor.getLong(2))
                if (!cursor.isNull(3)) endTime = Date(cursor.getLong(3))
                summaryData = cursor.getString(4)
                rawSummaryData = cursor.getBlob(5)
                deviceId = cursor.getLong(6)
                userId = cursor.getLong(7)
            }
            try {
                rows.add(decode(summary, parser, captured))
            } catch (e: Exception) {
                LOG.warn("Skipping unreadable common workout summary ({})", e.javaClass.simpleName)
            }
        }
        return rows
    }

    @JvmStatic
    fun decode(summary: BaseActivitySummary, parser: ActivitySummaryParser?, captured: Long): WorkoutSummaryInput {
        val localId = requireNotNull(summary.id)
        require(localId in 1 until ID_OFFSET)
        // Detached entity, no name, coordinates, GPX or raw-details path. false requests only
        // lightweight summary parsing; some brands intentionally never persist summary JSON.
        val parsed = if (summary.summaryData.isNullOrBlank() && summary.rawSummaryData != null && parser != null) {
            requireNotNull(parser.parseWorkout(summary, false))
        } else null
        val data = parsed?.data ?: ActivitySummaryData.fromJson(summary.summaryData)
        val normalized = parsed?.summary ?: summary
        val kind = ActivityKind.fromCode(normalized.activityKind)
        require(normalized.activityKind >= 0 && kind != ActivityKind.NOT_MEASURED &&
            kind != ActivityKind.NOT_WORN && !ActivityKind.isSleep(kind))
        val start = requireNotNull(normalized.startTime).time / 1000
        val end = requireNotNull(normalized.endTime).time / 1000
        val elapsed = Math.subtractExact(end, start)
        require(elapsed in 1..7 * 86400L && end <= captured)

        fun metric(key: String, units: Map<String, Double>): Double? {
            val entry = data.get(key) as? ActivitySummarySimpleEntry ?: return null
            val factor = units[entry.unit] ?: return null
            val value = (entry.value as? Number)?.toDouble()?.times(factor) ?: return null
            return value.takeIf { it.isFinite() && it >= 0 }
        }
        fun hr(key: String): Int? = metric(key, mapOf(UNIT_BPM to 1.0))?.let {
            it.takeIf { v -> v in 1.0..300.0 && v != 255.0 }?.toInt()
        }
        val active = metric(ACTIVE_SECONDS, mapOf(UNIT_SECONDS to 1.0,
            UNIT_MILLISECONDS to 0.001, UNIT_HOURS to 3600.0))
        require(active == null || active <= elapsed)
        val min = hr(HR_MIN)
        val max = hr(HR_MAX)
        val avg = hr(HR_AVG)
        val validRange = min == null || max == null || min <= max
        return WorkoutSummaryInput(ID_OFFSET + localId, 0, normalized.activityKind, start, end,
            active?.toLong() ?: elapsed, elapsed,
            metric(DISTANCE_METERS, mapOf(UNIT_METERS to 1.0, UNIT_KILOMETERS to 1000.0,
                UNIT_CM to 0.01, UNIT_YARD to 0.9144, UNIT_NAUTICAL_MILES to 1852.0)),
            // Total calories must never be passed off as active calories.
            metric(CALORIES_BURNT, mapOf(UNIT_KCAL to 1.0)),
            avg?.takeIf { validRange && (min == null || it >= min) && (max == null || it <= max) },
            min?.takeIf { validRange }, max?.takeIf { validRange })
    }
}
