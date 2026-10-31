package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import java.io.IOException;

/** One measurement's lifecycle. All calls run on the main thread. */
public final class HuaweiHeartRateMeasurement {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(HuaweiHeartRateMeasurement.class);
    public static final long TIMEOUT_MS = 20_000;

    interface Port {
        boolean acquire(Runnable cancel);
        void register();
        void send(byte type) throws IOException;
        void schedule(Runnable timeout, long delayMs);
        void cleanup(Runnable cancel, Runnable timeout);
        void result(int bpm, String error);
    }

    private final Port port;
    private final Runnable cancel = () -> finish(0, "cancelled");
    private final Runnable timeout = () -> finish(0, "timeout");
    private boolean started;
    private boolean finished;
    private boolean acquired;
    private boolean openAttempted;

    HuaweiHeartRateMeasurement(Port port) {
        this.port = port;
    }

    void start() {
        if (started) return;
        started = true;
        if (!port.acquire(cancel)) {
            finished = true;
            port.result(0, "busy");
            return;
        }
        acquired = true;
        try {
            port.register();
            port.schedule(timeout, TIMEOUT_MS);
            openAttempted = true;
            port.send((byte) 1);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Manual heart rate start failed", e);
            finish(0, "failed");
        }
    }

    void receive(int bpm) {
        if (started && !finished && bpm > 0 && bpm <= 255) finish(bpm, null);
    }

    void cancel() {
        cancel.run();
    }

    private void finish(int bpm, String error) {
        if (!started || finished) return;
        finished = true;
        try {
            if (openAttempted) port.send((byte) 2);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Manual heart rate stop failed", e);
            error = "stop_failed";
        } finally {
            try {
                if (acquired) port.cleanup(cancel, timeout);
            } finally {
                port.result(bpm, error);
            }
        }
    }
}
