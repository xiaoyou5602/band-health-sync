package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class HyperOsAlarmActionsTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private PendingIntent action(String name) {
        PendingIntent intent = PendingIntent.getBroadcast(context, 1,
                new Intent(name).putExtra("alarm_id", 123), PendingIntent.FLAG_IMMUTABLE);
        org.robolectric.Shadows.shadowOf(intent).setCreatorPackage("com.android.deskclock");
        return intent;
    }

    private Notification notification(PendingIntent dismiss, PendingIntent snooze) {
        return new Notification.Builder(context, "channel_id_deskclock_alarm")
                .addAction(new Notification.Action.Builder(0, "close", dismiss).build())
                .addAction(new Notification.Action.Builder(0, "later", snooze).build())
                .setDeleteIntent(dismiss).build();
    }

    @Test
    public void verifiedLayoutKeepsClockCreatedActionsAndAlarmIdentity() {
        PendingIntent dismiss = action("dismiss");
        PendingIntent snooze = action("snooze");
        PendingIntent[] actions = HyperOsAlarmActions.find("com.android.deskclock", "channel_id_deskclock_alarm", notification(dismiss, snooze));
        assertArrayEquals(new PendingIntent[]{dismiss, snooze}, actions);
        assertEquals(123, org.robolectric.Shadows.shadowOf(actions[0]).getSavedIntent().getIntExtra("alarm_id", 0));
    }

    @Test
    public void wrongPackageTimerOrReorderedButtonsAreRejected() {
        PendingIntent dismiss = action("dismiss");
        PendingIntent snooze = action("snooze");
        Notification notification = notification(dismiss, snooze);
        assertNull(HyperOsAlarmActions.find("other.clock", "channel_id_deskclock_alarm", notification));
        assertNull(HyperOsAlarmActions.find("com.android.deskclock", "channel_id_deskclock_timer", notification));
        notification.actions = new Notification.Action[]{notification.actions[1], notification.actions[0]};
        assertNull(HyperOsAlarmActions.find("com.android.deskclock", "channel_id_deskclock_alarm", notification));
    }

    @Test
    public void missingDuplicateOrForeignActionsAreRejected() {
        PendingIntent dismiss = action("dismiss");
        Notification notification = notification(dismiss, dismiss);
        assertNull(HyperOsAlarmActions.find("com.android.deskclock", "channel_id_deskclock_alarm", notification));
        notification = notification(dismiss, action("snooze"));
        org.robolectric.Shadows.shadowOf(dismiss).setCreatorPackage("other.app");
        assertNull(HyperOsAlarmActions.find("com.android.deskclock", "channel_id_deskclock_alarm", notification));
        notification.actions = null;
        assertNull(HyperOsAlarmActions.find("com.android.deskclock", "channel_id_deskclock_alarm", notification));
    }
}
