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

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Turns whatever the user typed into the endpoint that actually accepts uploads.
 *
 * The obvious thing to type is the domain. Left alone that posts to `/`, and the server answers a
 * 404 whose body is an HTML error page - a failure that looks like a server problem and says
 * nothing about the real cause. So the bare origin is completed here instead of being rejected.
 */
object SelfHostedHealthEndpoint {
    /** Ingest path of the self-hosted health server. */
    const val DEFAULT_PATH = "/api/health"

    /**
     * @return the endpoint to post to, or null when [raw] cannot be read as an http(s) URL at all.
     */
    @JvmStatic
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        // A scheme-less host is just as reasonable a thing to type as a full URL.
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val url = candidate.toHttpUrlOrNull() ?: return null

        return if (url.encodedPath == "/") {
            url.newBuilder().encodedPath(DEFAULT_PATH).build().toString()
        } else {
            url.toString()
        }
    }
}
