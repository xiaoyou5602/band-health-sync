package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import android.database.Cursor
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import org.slf4j.LoggerFactory

/** Read-only projection of existing normalization; never opens a track file. */
object SelfHostedWorkoutReader {
    private val LOG = LoggerFactory.getLogger(SelfHostedWorkoutReader::class.java)

    fun read(device: GBDevice, fromSeconds: Long, toSeconds: Long): List<WorkoutSummaryInput> {
        if (device.deviceCoordinator !is HuaweiCoordinator) return emptyList()
        return GBApplication.acquireDbReadOnly().use { db ->
            val selector = DBHelper.getDevice(device, db.daoSession) ?: return@use emptyList()
            // Explicit columns: never load GPX paths, raw blobs or coordinates from either table.
            db.database.rawQuery("""
                SELECT h.WORKOUT_ID,h.TYPE,b.ACTIVITY_KIND,b.START_TIME,b.END_TIME,b.SUMMARY_DATA,
                       h.DURATION,h.TOTAL_TIME,h.DISTANCE,h.CALORIES
                FROM BASE_ACTIVITY_SUMMARY b JOIN HUAWEI_WORKOUT_SUMMARY_SAMPLE h
                  ON b.DEVICE_ID=h.DEVICE_ID AND b.START_TIME=h.START_TIMESTAMP*1000
                WHERE b.DEVICE_ID=? AND b.START_TIME>=? AND b.START_TIME<? AND b.END_TIME<=?
                ORDER BY b.START_TIME,h.WORKOUT_ID
            """.trimIndent(), arrayOf(selector.id.toString(), (fromSeconds * 1000).toString(),
                (toSeconds * 1000).toString(), (toSeconds * 1000).toString())).use { cursor ->
                readRows(cursor)
            }
        }
    }

    /** Cursor access failures propagate as subsystem failures; only row decoding is isolated. */
    @JvmStatic
    fun readRows(cursor: Cursor): List<WorkoutSummaryInput> {
        val rows = mutableListOf<WorkoutSummaryInput>()
        while (cursor.moveToNext()) {
            fun long(index: Int): Long? = if (cursor.isNull(index)) null else cursor.getLong(index)
            // Materialize outside the record catch: a broken cursor is not a malformed summary.
            val row = RawRow(long(0), long(1), long(2), long(3), long(4),
                if (cursor.isNull(5)) null else cursor.getString(5), long(6), long(7), long(8), long(9))
            val decoded = try {
                decode(row)
            } catch (e: RuntimeException) {
                LOG.warn("Skipping unreadable workout summary ({})", e.javaClass.simpleName)
                continue
            }
            rows.add(decoded)
        }
        return rows
    }

    private data class RawRow(
        val id: Long?, val rawType: Long?, val kind: Long?, val start: Long?, val end: Long?,
        val summary: String?, val duration: Long?, val total: Long?, val distance: Long?, val calories: Long?
    )

    private fun decode(row: RawRow): WorkoutSummaryInput {
        val summary = ActivitySummaryData.fromJson(row.summary)
        fun number(key: String): Double? = summary.getNumber(key, null)?.toDouble()
        fun raw(value: Long?): Long? = value?.takeIf { it >= 0 }
        fun integral(value: Double?): Long? {
            if (value == null) return null
            require(value.isFinite() && value >= 0 && value < Long.MAX_VALUE.toDouble() && value == value.toLong().toDouble()) { "workout_summary_number" }
            return value.toLong()
        }
        fun heartRate(key: String): Int? = integral(number(key))?.let {
            require(it <= Int.MAX_VALUE) { "workout_summary_heart_rate" }
            it.toInt()
        }
        val id = requireNotNull(row.id)
        val rawType = requireNotNull(row.rawType)
        val kind = requireNotNull(row.kind)
        val start = requireNotNull(row.start)
        val end = requireNotNull(row.end)
        // Preserve signed-byte source representation, but never wrap arbitrary corrupt integers.
        require(rawType in -128L..255L && kind in 0L..Int.MAX_VALUE.toLong())
        require(start % 1000L == 0L && end % 1000L == 0L)
        val active = integral(number(ActivitySummaryEntries.ACTIVE_SECONDS)) ?: raw(row.duration)
            ?: throw IllegalArgumentException("workout_summary_duration_missing")
        return WorkoutSummaryInput(id, rawType.toInt() and 255, kind.toInt(), start / 1000, end / 1000,
            active, raw(row.total), number(ActivitySummaryEntries.DISTANCE_METERS) ?: raw(row.distance)?.toDouble(),
            number(ActivitySummaryEntries.CALORIES_BURNT) ?: raw(row.calories)?.toDouble(),
            heartRate(ActivitySummaryEntries.HR_AVG), heartRate(ActivitySummaryEntries.HR_MIN), heartRate(ActivitySummaryEntries.HR_MAX))
    }
}
