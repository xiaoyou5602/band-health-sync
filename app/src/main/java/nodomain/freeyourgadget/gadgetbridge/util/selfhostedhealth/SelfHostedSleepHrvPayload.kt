package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiSleepStatsSampleProvider
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Nightly firmware statistics, deliberately separate from measured HRV time series. */
data class SleepHrvInput(
    val startMillis: Long,
    val endMillis: Long,
    val averageMs: Int,
    val baselineLowerMs: Int,
    val baselineUpperMs: Int
)

object SelfHostedSleepHrvPayload {
    fun read(device: GBDevice, fromSeconds: Long, toSeconds: Long): List<SleepHrvInput> {
        if (device.deviceCoordinator !is HuaweiCoordinator) return emptyList()
        return GBApplication.acquireDbReadOnly().use { db ->
            HuaweiSleepStatsSampleProvider(device, db.daoSession)
                .getSleepSamples(maxOf(0L, fromSeconds * 1000L - 2L * 86400000L), toSeconds * 1000L)
                .filter { it.wakeupTime >= fromSeconds * 1000L && it.wakeupTime <= toSeconds * 1000L }
                .map { SleepHrvInput(it.timestamp, it.wakeupTime, it.avgHrv, it.minHrvBaseline, it.maxHrvBaseline) }
        }
    }

    @JvmStatic
    fun attach(base: SelfHostedHealthPayloadSet, rows: List<SleepHrvInput>, zone: ZoneId, captured: Long): SelfHostedHealthPayloadSet {
        val days = base.days.associateBy { it.date }.toMutableMap()
        val latest = rows.filter {
            it.averageMs in 1..200 && it.startMillis > 0 && it.endMillis > it.startMillis
                && it.endMillis - it.startMillis <= 2L * 86400000L
                && it.endMillis <= captured * 1000L
        }.sortedBy { it.endMillis }.associateBy {
            Instant.ofEpochMilli(it.endMillis).atZone(zone).toLocalDate().toString()
        }
        for ((date, row) in latest) {
            fun timestamp(millis: Long) = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(millis).atZone(zone)
            )
            val baselineValid = row.baselineLowerMs in 1..200
                && row.baselineUpperMs in row.baselineLowerMs..200
            val summary = JSONObject()
                .put("source", "huawei_sleep_statistics")
                .put("sleep_start", timestamp(row.startMillis))
                .put("sleep_end", timestamp(row.endMillis))
                .put("average_ms", row.averageMs)
                .put("baseline_lower_ms", if (baselineValid) row.baselineLowerMs else JSONObject.NULL)
                .put("baseline_upper_ms", if (baselineValid) row.baselineUpperMs else JSONObject.NULL)
                .put("captured_at", timestamp(captured * 1000L))
            val body = days[date]?.body?.let { JSONObject(it.toString()) } ?: JSONObject().put("date", date)
            body.put("sleep_hrv", JSONArray().put(summary))
            days[date] = SelfHostedHealthDay(date, body)
        }
        return SelfHostedHealthPayloadSet(days.toSortedMap().values.toList(), base.sleepUploadedThrough)
    }
}
