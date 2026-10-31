package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HuaweiHeartRateMeasurementTest {
    private static class Port implements HuaweiHeartRateMeasurement.Port {
        final List<String> events = new ArrayList<>();
        boolean available = true;
        int failType;
        Runnable timeout, cancel;
        int bpm, results;
        String error;
        public boolean acquire(Runnable cancel) { this.cancel = cancel; return available; }
        public void register() { events.add("register"); }
        public void send(byte type) throws IOException {
            events.add("send" + type);
            if (type == failType) throw new IOException("test failure");
        }
        public void schedule(Runnable timeout, long delayMs) {
            assertEquals(20_000, delayMs);
            this.timeout = timeout;
        }
        public void cleanup(Runnable cancel, Runnable timeout) { events.add("cleanup"); }
        public void result(int bpm, String error) { this.bpm = bpm; this.error = error; results++; }
    }

    @Test public void registersBeforeOpenAndPublishesOnlyFirstNonzeroSample() {
        Port port = new Port();
        HuaweiHeartRateMeasurement session = new HuaweiHeartRateMeasurement(port);
        session.start(); session.start();
        session.receive(0);
        assertEquals(0, port.results);
        session.receive(74); session.receive(75); session.cancel(); port.timeout.run();
        assertEquals(List.of("register", "send1", "send2", "cleanup"), port.events);
        assertEquals(1, port.results);
        assertEquals(74, port.bpm);
        assertNull(port.error);
    }

    @Test public void timeoutStopsAndReleasesSession() {
        Port port = new Port();
        new HuaweiHeartRateMeasurement(port).start(); port.timeout.run();
        assertEquals("timeout", port.error);
        assertEquals(List.of("register", "send1", "send2", "cleanup"), port.events);
    }

    @Test public void cancelOrDisconnectStopsExactlyOnce() {
        Port port = new Port();
        new HuaweiHeartRateMeasurement(port).start(); port.cancel.run(); port.cancel.run();
        assertEquals("cancelled", port.error);
        assertEquals(1, port.results);
        assertEquals(List.of("register", "send1", "send2", "cleanup"), port.events);
    }

    @Test public void openFailureStillAttemptsCloseAndCleansUp() {
        Port port = new Port(); port.failType = 1;
        new HuaweiHeartRateMeasurement(port).start();
        assertEquals("failed", port.error);
        assertEquals(List.of("register", "send1", "send2", "cleanup"), port.events);
    }

    @Test public void stopFailureDoesNotLeaveReceiverOrPretendStopSucceeded() {
        Port port = new Port(); port.failType = 2;
        HuaweiHeartRateMeasurement session = new HuaweiHeartRateMeasurement(port);
        session.start(); session.receive(75);
        assertEquals("stop_failed", port.error);
        assertEquals(75, port.bpm);
        assertEquals("cleanup", port.events.get(port.events.size() - 1));
    }

    @Test public void busyRejectedWithoutClosingOtherMeasurement() {
        Port port = new Port(); port.available = false;
        HuaweiHeartRateMeasurement session = new HuaweiHeartRateMeasurement(port);
        session.start(); session.cancel(); session.receive(80);
        assertEquals("busy", port.error);
        assertTrue(port.events.isEmpty());
        assertEquals(1, port.results);
    }
}
