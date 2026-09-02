package nodomain.freeyourgadget.gadgetbridge.util.healthconnect;

import android.content.Intent;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HcWebhookSyncTriggerTest extends TestBase {
    @Test
    public void createsExplicitForegroundBroadcastForHcWebhook() {
        final Intent intent = HcWebhookSyncTrigger.createIntent();

        assertEquals("com.hcwebhook.app.SCHEDULED_SYNC", intent.getAction());
        assertNotNull(intent.getComponent());
        assertEquals("com.hcwebhook.app", intent.getComponent().getPackageName());
        assertEquals(
                "com.hcwebhook.app.ScheduledSyncReceiver",
                intent.getComponent().getClassName()
        );
        assertTrue((intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0);
    }
}
