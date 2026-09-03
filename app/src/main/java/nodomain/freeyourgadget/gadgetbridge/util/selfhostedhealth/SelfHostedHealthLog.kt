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

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * One recorded `POST /api/health` for the sync log.
 *
 * A single upload run posts one body per day, so one entry is one day's upload: its own status
 * code, response time and payload. That is exactly what the detail screen shows, so the log is
 * kept at this granularity rather than one entry per run.
 */
data class SelfHostedHealthLogEntry(
    /** Wall-clock moment the request finished, epoch millis. */
    val timestampMs: Long,
    /** Friendly device name the data came from, for the row subtitle. */
    val deviceName: String,
    /** The endpoint actually posted to. */
    val url: String,
    /** Calendar day this payload is filed under, "yyyy-MM-dd". */
    val date: String,
    /** True when the user pressed "Upload now"; false for event-driven and periodic runs. */
    val manual: Boolean,
    /** Number of data records in the payload (steps + heart-rate points + sleep sessions). */
    val records: Int,
    /** HTTP status code, 0 when the request never got an answer. */
    val httpCode: Int,
    /** Round-trip time in milliseconds, 0 when nothing was sent. */
    val responseTimeMs: Long,
    val success: Boolean,
    /** Failure detail, null on success. */
    val message: String?,
    /** The JSON body that was posted, pretty-printed; truncated if very large. */
    val payload: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_TIMESTAMP, timestampMs)
        put(KEY_DEVICE_NAME, deviceName)
        put(KEY_URL, url)
        put(KEY_DATE, date)
        put(KEY_MANUAL, manual)
        put(KEY_RECORDS, records)
        put(KEY_HTTP_CODE, httpCode)
        put(KEY_RESPONSE_TIME, responseTimeMs)
        put(KEY_SUCCESS, success)
        // JSONObject.put with a null drops the key, which is what we want for "no message".
        put(KEY_MESSAGE, message)
        put(KEY_PAYLOAD, payload)
    }

    companion object {
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_URL = "url"
        private const val KEY_DATE = "date"
        private const val KEY_MANUAL = "manual"
        private const val KEY_RECORDS = "records"
        private const val KEY_HTTP_CODE = "http_code"
        private const val KEY_RESPONSE_TIME = "response_time_ms"
        private const val KEY_SUCCESS = "success"
        private const val KEY_MESSAGE = "message"
        private const val KEY_PAYLOAD = "payload"

        @JvmStatic
        fun fromJson(json: JSONObject): SelfHostedHealthLogEntry = SelfHostedHealthLogEntry(
            timestampMs = json.optLong(KEY_TIMESTAMP),
            deviceName = json.optString(KEY_DEVICE_NAME),
            url = json.optString(KEY_URL),
            date = json.optString(KEY_DATE),
            manual = json.optBoolean(KEY_MANUAL),
            records = json.optInt(KEY_RECORDS),
            httpCode = json.optInt(KEY_HTTP_CODE),
            responseTimeMs = json.optLong(KEY_RESPONSE_TIME),
            success = json.optBoolean(KEY_SUCCESS),
            message = if (json.isNull(KEY_MESSAGE)) null else json.optString(KEY_MESSAGE).ifEmpty { null },
            payload = json.optString(KEY_PAYLOAD)
        )
    }
}

/**
 * A capped, newest-first history of self-hosted health uploads, stored as one JSON file in the
 * app's private storage.
 *
 * Split like [SelfHostedHealthPayload]: the serialize / deserialize / merge core is pure so it can
 * be unit tested, and the [append] / [read] / [clear] wrappers are the only part that touches the
 * filesystem. Those wrappers are synchronized because the worker writes from a background thread
 * while the log screen reads on the main thread, both in the same process.
 */
object SelfHostedHealthLog {
    private val LOG = LoggerFactory.getLogger(SelfHostedHealthLog::class.java)

    private const val FILE_NAME = "selfhosted_health_log.json"

    /** How many uploads to keep. Matches the "last N" feel of the reference and caps the file. */
    const val MAX_ENTRIES = 100

    /** A single stored payload is truncated to this, so one huge body cannot bloat the file. */
    const val MAX_PAYLOAD_CHARS = 65536

    // --- pure core (no Android, no filesystem) ---

    @JvmStatic
    fun serialize(entries: List<SelfHostedHealthLogEntry>): String {
        val array = JSONArray()
        for (entry in entries) {
            array.put(entry.toJson())
        }
        return array.toString()
    }

    @JvmStatic
    fun deserialize(json: String?): List<SelfHostedHealthLogEntry> {
        if (json.isNullOrBlank()) {
            return emptyList()
        }
        return try {
            val array = JSONArray(json)
            val out = ArrayList<SelfHostedHealthLogEntry>(array.length())
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { out.add(SelfHostedHealthLogEntry.fromJson(it)) }
            }
            out
        } catch (e: JSONException) {
            LOG.warn("Could not parse self-hosted health log, starting fresh", e)
            emptyList()
        }
    }

    /**
     * Combines the stored entries with a freshly recorded batch, newest first, capped at [max].
     * The result order does not depend on the batch's own order: everything is sorted by timestamp,
     * so a caller need not know the file invariant.
     */
    @JvmStatic
    fun merge(
        existing: List<SelfHostedHealthLogEntry>,
        batch: List<SelfHostedHealthLogEntry>,
        max: Int = MAX_ENTRIES
    ): List<SelfHostedHealthLogEntry> {
        if (batch.isEmpty()) {
            return existing.take(max)
        }
        return (batch + existing)
            .sortedByDescending { it.timestampMs }
            .take(max)
    }

    /** Caps an entry's payload so one pathological body cannot dominate the file. */
    @JvmStatic
    fun cap(entry: SelfHostedHealthLogEntry): SelfHostedHealthLogEntry =
        if (entry.payload.length <= MAX_PAYLOAD_CHARS) {
            entry
        } else {
            entry.copy(payload = entry.payload.take(MAX_PAYLOAD_CHARS) + "\n…")
        }

    // --- filesystem wrappers ---

    @JvmStatic
    @Synchronized
    fun read(context: Context): List<SelfHostedHealthLogEntry> {
        val file = file(context)
        if (!file.exists()) {
            return emptyList()
        }
        return try {
            deserialize(file.readText())
        } catch (e: Exception) {
            LOG.warn("Could not read self-hosted health log", e)
            emptyList()
        }
    }

    @JvmStatic
    @Synchronized
    fun append(context: Context, batch: List<SelfHostedHealthLogEntry>) {
        if (batch.isEmpty()) {
            return
        }
        val merged = merge(read(context), batch.map { cap(it) })
        try {
            file(context).writeText(serialize(merged))
        } catch (e: Exception) {
            LOG.warn("Could not write self-hosted health log", e)
        }
    }

    @JvmStatic
    @Synchronized
    fun clear(context: Context) {
        try {
            val file = file(context)
            if (file.exists() && !file.delete()) {
                // Delete can fail on some filesystems; blanking it has the same visible effect.
                file.writeText("[]")
            }
        } catch (e: Exception) {
            LOG.warn("Could not clear self-hosted health log", e)
        }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
