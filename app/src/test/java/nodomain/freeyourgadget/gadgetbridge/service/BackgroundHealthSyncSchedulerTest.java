package nodomain.freeyourgadget.gadgetbridge.service;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class BackgroundHealthSyncSchedulerTest {
    private static class Fixture implements BackgroundHealthSyncScheduler.Executor {
        final List<Runnable> pending = new ArrayList<>();
        boolean initialized = true;
        boolean busy;
        long interval = 180_000;
        long delay;
        int fetches;
        final BackgroundHealthSyncScheduler scheduler = new BackgroundHealthSyncScheduler(this,
                () -> new BackgroundHealthSyncScheduler.State(initialized, busy, interval),
                () -> { fetches++; busy = true; });

        public void postDelayed(Runnable task, long millis) { pending.add(task); delay = millis; }
        public void removeCallbacks(Runnable task) { pending.remove(task); }
        void tick() { pending.remove(0).run(); }
    }

    @Test public void slowSyncWaitsForCompletionAndUnrelatedUpdatesDoNotPostponeTimer() {
        Fixture f = new Fixture();
        f.scheduler.update();
        f.scheduler.update();
        assertEquals(1, f.pending.size());
        f.tick();
        assertEquals(1, f.fetches);
        for (int i = 0; i < 10; i++) f.scheduler.update();
        assertTrue(f.pending.isEmpty());
        f.busy = false;
        f.scheduler.update();
        assertEquals(1, f.pending.size());
        assertEquals(180_000, f.delay);
    }

    @Test public void manualSyncCancelsTimerAndCompletingItStartsFreshInterval() {
        Fixture f = new Fixture();
        f.scheduler.update();
        f.busy = true;
        f.scheduler.update();
        assertTrue(f.pending.isEmpty());
        f.busy = false;
        f.scheduler.update();
        f.tick();
        assertEquals(1, f.fetches);
    }

    @Test public void timerRechecksLiveBusyStateBeforeBroadcastArrives() {
        Fixture f = new Fixture();
        f.scheduler.update();
        f.busy = true;
        f.tick();
        assertEquals(0, f.fetches);
        assertTrue(f.pending.isEmpty());
    }

    @Test public void disconnectCancelsAndReconnectRestarts() {
        Fixture f = new Fixture();
        f.scheduler.update();
        f.tick();
        f.initialized = false;
        f.busy = false;
        f.scheduler.update();
        assertTrue(f.pending.isEmpty());
        f.initialized = true;
        f.scheduler.update();
        assertEquals(1, f.pending.size());
    }

    @Test public void disableDuringFetchDoesNotRestartOnCompletion() {
        Fixture f = new Fixture();
        f.scheduler.update();
        f.tick();
        f.interval = 0;
        f.scheduler.update();
        f.busy = false;
        f.scheduler.update();
        assertTrue(f.pending.isEmpty());
    }

    @Test public void intervalChangesApplyImmediatelyAndStaleTimerDoesNotFetch() {
        Fixture f = new Fixture();
        f.scheduler.update();
        f.interval = 600_000;
        f.tick();
        assertEquals(0, f.fetches);
        assertEquals(600_000, f.delay);
        assertEquals(1, f.pending.size());
    }

    @Test public void serviceStopIsTerminalEvenIfLateEventsArrive() {
        Fixture f = new Fixture();
        f.scheduler.update();
        Runnable late = f.pending.get(0);
        f.scheduler.stop();
        late.run();
        f.scheduler.update();
        assertTrue(f.pending.isEmpty());
        assertEquals(0, f.fetches);
    }

    @Test public void devicesHaveIndependentSchedules() {
        Fixture a = new Fixture();
        Fixture b = new Fixture();
        a.scheduler.update();
        b.scheduler.update();
        a.tick();
        b.tick();
        a.initialized = false;
        a.scheduler.update();
        b.busy = false;
        b.scheduler.update();
        assertTrue(a.pending.isEmpty());
        assertEquals(1, b.pending.size());
    }

    @Test public void validatesIntervals() {
        assertEquals(0, BackgroundHealthSyncScheduler.intervalMillis("0"));
        assertEquals(60_000, BackgroundHealthSyncScheduler.intervalMillis("1"));
        assertEquals(180_000, BackgroundHealthSyncScheduler.intervalMillis("3"));
        assertEquals(300_000, BackgroundHealthSyncScheduler.intervalMillis("5"));
        assertEquals(600_000, BackgroundHealthSyncScheduler.intervalMillis("10"));
        assertEquals(180_000, BackgroundHealthSyncScheduler.intervalMillis("garbage"));
        assertEquals(180_000, BackgroundHealthSyncScheduler.intervalMillis("-1"));
    }
}
