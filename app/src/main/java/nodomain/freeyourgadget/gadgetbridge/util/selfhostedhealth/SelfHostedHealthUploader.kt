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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Result of one `POST /api/health`.
 *
 * [retryable] separates "the server said no" from "we could not reach it": a 4xx will fail again
 * with the same body and must not be retried forever, while a timeout or a 5xx usually will not.
 *
 * [httpCode] and [responseTimeMs] exist for the sync log: the code is 0 when the request never got
 * an answer (it could not be built, or the connection failed), and the time is how long the call
 * took wall-clock, 0 when nothing was sent.
 */
sealed class SelfHostedHealthUploadResult {
    abstract val httpCode: Int
    abstract val responseTimeMs: Long

    data class Success(
        override val httpCode: Int,
        override val responseTimeMs: Long
    ) : SelfHostedHealthUploadResult()

    data class Failure(
        val message: String,
        val retryable: Boolean,
        override val httpCode: Int,
        override val responseTimeMs: Long
    ) : SelfHostedHealthUploadResult()
}

/**
 * Posts prepared day payloads straight to the user's own health server.
 *
 * This is the whole reason the fork can drop Health Connect and the third party webhook app: the
 * data already sits in Gadgetbridge's database, so nothing has to be handed to another process.
 */
class SelfHostedHealthUploader(
    private val client: OkHttpClient = defaultClient
) {
    fun upload(url: String, token: String, body: String): SelfHostedHealthUploadResult {
        // Started before the request is even built so the "could not build it" path still reports a
        // (tiny) time rather than a bogus one; reset once the call is actually about to go out.
        var startNanos = System.nanoTime()
        return try {
            // Built inside the try on purpose: a token with a stray newline or a malformed URL makes
            // Request.Builder throw IllegalArgumentException (a header value cannot hold a control
            // char). Left outside, that would escape the worker uncaught and WorkManager would mark
            // the run failed without ever storing a status - the "Upload now did nothing" report.
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${sanitizeToken(token)}")
                .post(body.toRequestBody(JSON))
                .build()

            startNanos = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val elapsedMs = elapsedMs(startNanos)
                if (response.isSuccessful) {
                    SelfHostedHealthUploadResult.Success(response.code, elapsedMs)
                } else {
                    // The server answers a rejected body with a plain { "error": ... }; carrying it
                    // into the settings screen is the difference between "it does not work" and
                    // "the token is wrong".
                    val detail = summarizeErrorBody(response.body?.string())
                    SelfHostedHealthUploadResult.Failure(
                        "HTTP ${response.code}${if (detail.isEmpty()) "" else ": $detail"}",
                        retryable = response.code >= 500 || response.code == 429,
                        httpCode = response.code,
                        responseTimeMs = elapsedMs
                    )
                }
            }
        } catch (e: IOException) {
            LOG.warn("Health upload to {} failed", url, e)
            SelfHostedHealthUploadResult.Failure(
                e.message ?: e.javaClass.simpleName,
                retryable = true,
                httpCode = 0,
                responseTimeMs = elapsedMs(startNanos)
            )
        } catch (e: IllegalArgumentException) {
            // A malformed URL or a token that still carries a control char after sanitizing: the
            // request could not even be built, so retrying the same inputs cannot help.
            LOG.warn("Could not build health upload request for {}", url, e)
            SelfHostedHealthUploadResult.Failure(
                e.message ?: e.javaClass.simpleName,
                retryable = false,
                httpCode = 0,
                responseTimeMs = 0L
            )
        }
    }

    private fun elapsedMs(startNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)

    companion object {
        private val LOG = LoggerFactory.getLogger(SelfHostedHealthUploader::class.java)
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val HTML_TAG = Regex("<[^>]*>")
        private val WHITESPACE = Regex("\\s+")

        /**
         * A bearer token is a single opaque string with no internal whitespace, but the settings
         * field is pasted into and a wrapped copy drops a newline into the middle. That newline is a
         * control char an HTTP header value cannot hold, so it has to come out before the token ever
         * reaches [Request.Builder.header]. Stripping every whitespace char is safe for a token and
         * fixes both the leading/trailing and the mid-string cases in one pass.
         */
        @JvmStatic
        fun sanitizeToken(token: String?): String = token.orEmpty().replace(WHITESPACE, "")

        /**
         * A wrong path or a proxy in the way answers with an HTML error page, and dumping its markup
         * into a one-line status turns the useful part ("Cannot POST /") into noise. Strip the tags
         * so the sentence survives.
         */
        @JvmStatic
        fun summarizeErrorBody(body: String?): String = body.orEmpty()
            .replace(HTML_TAG, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .take(120)

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
