package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Build;

/** The clock's own notification actions preserve the exact alarm identity and snooze settings. */
final class HyperOsAlarmActions {
    private HyperOsAlarmActions() {}

    static PendingIntent[] find(String packageName, String channelId, Notification notification) {
        // Verified NotificationUtil.buildNotificationWithAlarmAlert layout: dismiss, snooze.
        // deleteIntent is the same dismiss PendingIntent, providing a structural cross-check.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || !"com.android.deskclock".equals(packageName)
                || !"channel_id_deskclock_alarm".equals(channelId)
                || notification.actions == null || notification.actions.length != 2
                || !isClockAction(notification.actions[0], packageName)
                || !isClockAction(notification.actions[1], packageName)
                || !notification.actions[0].actionIntent.equals(notification.deleteIntent)
                || notification.actions[0].actionIntent.equals(notification.actions[1].actionIntent)) return null;
        return new PendingIntent[]{notification.actions[0].actionIntent, notification.actions[1].actionIntent};
    }

    private static boolean isClockAction(Notification.Action action, String packageName) {
        return action != null && action.actionIntent != null
                && packageName.equals(action.actionIntent.getCreatorPackage())
                && action.actionIntent.isBroadcast();
    }
}
