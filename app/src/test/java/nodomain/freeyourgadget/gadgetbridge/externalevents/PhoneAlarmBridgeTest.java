package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhoneAlarmBridgeTest {
    private FakeDispatcher dispatcher;
    private FakeScheduler scheduler;
    private PhoneAlarmBridge bridge;

    @Before
    public void setUp() {
        dispatcher = new FakeDispatcher();
        scheduler = new FakeScheduler();
        bridge = new PhoneAlarmBridge(dispatcher, () -> true, scheduler);
    }

    @Test
    public void broadcastAndNotificationStartsAreDeduplicated() {
        bridge.updateSource(null, "broadcast:clock", "clock", true, true);
        bridge.updateSource(null, "notification:key", "clock", true, true);

        assertEquals(1, dispatcher.startCount);
        assertEquals(0, dispatcher.stopCount);
        assertTrue(bridge.isNotificationTracked("key"));
    }

    @Test
    public void notificationRemovalEndsMergedAlarmOnce() {
        bridge.updateSource(null, "broadcast:clock", "clock", true, true);
        bridge.updateSource(null, "notification:key", "clock", true, true);
        bridge.onNotificationRemoved("key");
        bridge.updateSource(null, "broadcast:clock", "clock", false, true);

        assertEquals(1, dispatcher.startCount);
        assertEquals(1, dispatcher.stopCount);
        assertFalse(bridge.isNotificationTracked("key"));
    }

    @Test
    public void nonRingingUpdateEndsTrackedNotification() {
        bridge.updateSource(null, "notification:key", "clock", true, true);
        bridge.updateSource(null, "notification:key", "clock", false, true);

        assertEquals(0, dispatcher.stopCount);
        scheduler.runAll();

        assertEquals(1, dispatcher.startCount);
        assertEquals(1, dispatcher.stopCount);
    }

    @Test
    public void notificationReplacementWithinGraceKeepsSingleSession() {
        bridge.updateSource(null, "notification:old", "clock", true, true);
        bridge.onNotificationRemoved("old");
        bridge.updateSource(null, "notification:new", "clock", true, true);
        scheduler.runAll();

        assertEquals(1, dispatcher.startCount);
        assertEquals(0, dispatcher.stopCount);
        assertFalse(bridge.isNotificationTracked("old"));
        assertTrue(bridge.isNotificationTracked("new"));
        bridge.onNotificationRemoved("new");
        scheduler.runAll();
        assertEquals(1, dispatcher.stopCount);
    }

    @Test
    public void removingOneOfTwoNotificationsKeepsSessionUntilBothEnd() {
        bridge.updateSource(null, "notification:first", "clock", true, true);
        bridge.updateSource(null, "notification:second", "clock", true, true);
        bridge.onNotificationRemoved("first");
        scheduler.runAll();
        assertEquals(0, dispatcher.stopCount);

        bridge.onNotificationRemoved("second");
        scheduler.runAll();
        assertEquals(1, dispatcher.startCount);
        assertEquals(1, dispatcher.stopCount);
    }

    @Test
    public void resetStopsAndCancelsPendingRemovalOnce() {
        bridge.updateSource(null, "notification:key", "clock", true, true);
        bridge.onNotificationRemoved("key");
        bridge.reset();
        scheduler.runAll();
        bridge.reset();

        assertEquals(1, dispatcher.startCount);
        assertEquals(1, dispatcher.stopCount);
    }

    @Test
    public void disablingStopsCurrentAlarmAndBlocksNewStarts() {
        bridge.updateSource(null, "notification:key", "clock", true, true);
        bridge.onEnabledChanged(false);
        bridge.updateSource(null, "notification:next", "clock", true, false);

        assertEquals(1, dispatcher.startCount);
        assertEquals(1, dispatcher.stopCount);
        assertFalse(bridge.isNotificationTracked("next"));
    }

    @Test
    public void deviceActionsUseMatchingLiveSessionAndAreDeduplicated() {
        final int[] calls = {0, 0};
        bridge.updateSource(null, "notification:key", "clock", true, true);
        bridge.setNotificationActions("key", () -> { calls[0]++; return true; }, () -> { calls[1]++; return true; });
        assertFalse(bridge.onDeviceAction(99, 2));
        assertFalse(bridge.onDeviceAction(42, 9));
        assertTrue(bridge.onDeviceAction(42, 2));
        assertFalse(bridge.onDeviceAction(42, 2));
        assertFalse(bridge.onDeviceAction(42, 1));
        assertEquals(1, calls[0]);
        assertEquals(0, calls[1]);
        assertEquals(0, dispatcher.stopCount);
        bridge.onNotificationRemoved("key");
        scheduler.runAll();
        assertEquals(1, dispatcher.stopCount);
        bridge.updateSource(null, "notification:next", "clock", true, true);
        bridge.setNotificationActions("next", () -> false, () -> { calls[1]++; return true; });
        assertTrue(bridge.onDeviceAction(42, 1));
        assertEquals(1, calls[1]);
    }

    @Test
    public void removedOrReplacedNotificationsCannotInvokeOldActions() {
        bridge.updateSource(null, "notification:old", "clock", true, true);
        bridge.setNotificationActions("old", () -> { throw new AssertionError("stale action"); }, () -> false);
        bridge.onNotificationRemoved("old");
        assertFalse(bridge.onDeviceAction(42, 2));
        bridge.updateSource(null, "notification:new", "clock", true, true);
        bridge.setNotificationActions("new", () -> true, () -> true);
        assertTrue(bridge.onDeviceAction(42, 2));
        bridge.reset();
        assertFalse(bridge.onDeviceAction(42, 2));
    }

    @Test
    public void ambiguousOrUnavailableActionsAreNotSent() {
        bridge.updateSource(null, "notification:first", "clock", true, true);
        bridge.setNotificationActions("first", () -> true, () -> true);
        bridge.updateSource(null, "notification:second", "clock", true, true);
        bridge.setNotificationActions("second", () -> true, () -> true);
        assertFalse(bridge.onDeviceAction(42, 2));
        bridge.onNotificationRemoved("second");
        bridge.setNotificationActions("first", () -> false, () -> false);
        assertFalse(bridge.onDeviceAction(42, 2));
        bridge.setNotificationActions("first", () -> true, () -> true);
        assertTrue(bridge.onDeviceAction(42, 2));
        bridge.onEnabledChanged(false);
        assertFalse(bridge.onDeviceAction(42, 1));
    }

    private static final class FakeDispatcher implements PhoneAlarmBridge.AlarmDispatcher {
        private int startCount;
        private int stopCount;

        @Override
        public int start(final Context context, final String packageName) {
            startCount++;
            return 42;
        }

        @Override
        public void stop(final int notificationId) {
            assertEquals(42, notificationId);
            stopCount++;
        }
    }

    private static final class FakeScheduler implements PhoneAlarmBridge.Scheduler {
        private final List<Runnable> scheduled = new ArrayList<>();

        @Override
        public void schedule(final Runnable action, final long delayMillis) {
            assertEquals(1500, delayMillis);
            scheduled.add(action);
        }

        @Override
        public void cancel(final Runnable action) {
            scheduled.remove(action);
        }

        void runAll() {
            for (final Runnable action : new ArrayList<>(scheduled)) {
                scheduled.remove(action);
                action.run();
            }
        }
    }
}
