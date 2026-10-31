/*  Copyright (C) 2026 toge

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
package nodomain.freeyourgadget.gadgetbridge.service;

import java.util.function.Supplier;

/** Event-driven, completion-relative scheduling. All calls run on the service's main thread. */
public final class BackgroundHealthSyncScheduler {
    public static final String PREF_INTERVAL = "background_health_sync_interval";

    public interface Executor {
        void postDelayed(Runnable task, long delayMillis);
        void removeCallbacks(Runnable task);
    }

    public record State(boolean initialized, boolean busy, long intervalMillis) { }

    private final Executor executor;
    private final Supplier<State> state;
    private final Runnable fetch;
    private final Runnable timer = this::run;
    private long scheduledInterval;
    private boolean scheduled;
    private boolean stopped;

    public BackgroundHealthSyncScheduler(Executor executor, Supplier<State> state, Runnable fetch) {
        this.executor = executor;
        this.state = state;
        this.fetch = fetch;
    }

    public static long intervalMillis(String value) {
        return switch (value) {
            case "0" -> 0;
            case "1" -> 60_000L;
            case "5" -> 300_000L;
            case "10" -> 600_000L;
            default -> 180_000L;
        };
    }

    public void update() {
        if (stopped) return;
        final State current = state.get();
        if (!current.initialized() || current.busy() || current.intervalMillis() <= 0) {
            cancel();
            return;
        }
        if (scheduled && scheduledInterval == current.intervalMillis()) return;
        cancel();
        scheduled = true;
        scheduledInterval = current.intervalMillis();
        executor.postDelayed(timer, scheduledInterval);
    }

    private void run() {
        scheduled = false;
        if (stopped) return;
        final State current = state.get();
        if (current.initialized() && !current.busy() && current.intervalMillis() > 0
                && current.intervalMillis() == scheduledInterval) {
            // The fetch entry must reserve the device synchronously, before sending commands.
            // Only the idle update after the last operation will then schedule another round.
            try {
                fetch.run();
            } finally {
                update();
            }
        } else {
            update();
        }
    }

    public void stop() {
        stopped = true;
        cancel();
    }

    private void cancel() {
        executor.removeCallbacks(timer);
        scheduled = false;
    }
}
