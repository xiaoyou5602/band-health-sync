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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands completed Health Connect writes directly to the installed HCWebhook app.
 *
 * <p>HCWebhook still owns reading Health Connect and uploading to the configured server. This
 * explicit broadcast only asks it to run its existing sync entry point, avoiding the otherwise
 * unbounded wait for Android's periodic background scheduling.</p>
 */
public final class HcWebhookSyncTrigger {
    static final String ACTION_SCHEDULED_SYNC = "com.hcwebhook.app.SCHEDULED_SYNC";
    static final String RECEIVER_PACKAGE = "com.hcwebhook.app";
    static final String RECEIVER_CLASS = "com.hcwebhook.app.ScheduledSyncReceiver";

    private static final Logger LOG = LoggerFactory.getLogger(HcWebhookSyncTrigger.class);

    private HcWebhookSyncTrigger() {
    }

    static Intent createIntent() {
        return new Intent(ACTION_SCHEDULED_SYNC)
                .setComponent(new ComponentName(RECEIVER_PACKAGE, RECEIVER_CLASS))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
    }

    public static void trigger(Context context) {
        try {
            context.sendBroadcast(createIntent());
            LOG.info("Requested immediate HCWebhook sync after Health Connect handoff");
        } catch (RuntimeException e) {
            // HCWebhook is optional. Its absence or a future receiver contract change must not turn
            // a successful Health Connect export into a failed Gadgetbridge worker.
            LOG.warn("Unable to request immediate HCWebhook sync; periodic upload remains the fallback", e);
        }
    }
}
