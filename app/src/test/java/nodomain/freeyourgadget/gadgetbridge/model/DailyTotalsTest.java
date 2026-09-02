package nodomain.freeyourgadget.gadgetbridge.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;

public class DailyTotalsTest {
    @Test
    public void latestValidHeartRateIsUsed() {
        assertEquals(82, DailyTotals.calculateLatestHeartRate(Arrays.asList(
                sample(100, 70),
                sample(300, ActivitySample.NOT_MEASURED),
                sample(200, 82)
        ), 10, 250));
    }

    @Test
    public void missingHeartRateUsesSentinel() {
        assertEquals(-1, DailyTotals.calculateLatestHeartRate(Arrays.asList(
                sample(100, ActivitySample.NOT_MEASURED),
                sample(200, 0)
        ), 10, 250));
    }

    private static ActivitySample sample(int timestamp, int heartRate) {
        return new ActivitySample() {
            @Override public int getTimestamp() { return timestamp; }
            @Override public ActivityKind getKind() { return ActivityKind.ACTIVITY; }
            @Override public int getSteps() { return 0; }
            @Override public SampleProvider<?> getProvider() { return null; }
            @Override public int getRawKind() { return ActivityKind.ACTIVITY.getCode(); }
            @Override public int getRawIntensity() { return NOT_MEASURED; }
            @Override public float getIntensity() { return 0; }
            @Override public int getDistanceCm() { return NOT_MEASURED; }
            @Override public int getActiveCalories() { return NOT_MEASURED; }
            @Override public int getHeartRate() { return heartRate; }
            @Override public void setHeartRate(int value) {}
        };
    }
}
