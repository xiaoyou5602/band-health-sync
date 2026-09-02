package nodomain.freeyourgadget.gadgetbridge;

import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

import static org.junit.Assert.assertEquals;

public class WidgetTest {
    private static final TimeZone TIME_ZONE = TimeZone.getTimeZone("Asia/Singapore");

    @Test
    public void sleepIsAttributedToSessionEndDate() {
        List<ActivitySample> samples = Arrays.asList(
                sleep(2026, Calendar.SEPTEMBER, 1, 23, 0, ActivityKind.LIGHT_SLEEP),
                sleep(2026, Calendar.SEPTEMBER, 2, 0, 0, ActivityKind.DEEP_SLEEP),
                sleep(2026, Calendar.SEPTEMBER, 2, 1, 0, ActivityKind.LIGHT_SLEEP),
                sleep(2026, Calendar.SEPTEMBER, 2, 7, 0, ActivityKind.LIGHT_SLEEP),
                activity(2026, Calendar.SEPTEMBER, 2, 7, 1)
        );

        assertEquals(0, Widget.calculateSleepMinutesForWakeDate(
                samples, day(2026, Calendar.SEPTEMBER, 1)));
        assertEquals(8 * 60, Widget.calculateSleepMinutesForWakeDate(
                samples, day(2026, Calendar.SEPTEMBER, 2)));
    }

    @Test
    public void zeroSleepRemainsZero() {
        assertEquals(0, Widget.calculateSleepMinutesForWakeDate(
                Arrays.asList(activity(2026, Calendar.SEPTEMBER, 2, 7, 1)),
                day(2026, Calendar.SEPTEMBER, 2)));
    }

    private static Calendar day(int year, int month, int date) {
        Calendar calendar = new GregorianCalendar(TIME_ZONE);
        calendar.clear();
        calendar.set(year, month, date);
        return calendar;
    }

    private static MockSample sleep(int year, int month, int date, int hour, int minute,
                                    ActivityKind kind) {
        return new MockSample(timestamp(year, month, date, hour, minute), kind, 0);
    }

    private static MockSample activity(int year, int month, int date, int hour, int minute) {
        return new MockSample(timestamp(year, month, date, hour, minute), ActivityKind.ACTIVITY, 100);
    }

    private static int timestamp(int year, int month, int date, int hour, int minute) {
        Calendar calendar = new GregorianCalendar(TIME_ZONE);
        calendar.clear();
        calendar.set(year, month, date, hour, minute);
        return (int) (calendar.getTimeInMillis() / 1000L);
    }

    private static class MockSample implements ActivitySample {
        private final int timestamp;
        private final ActivityKind kind;
        private final int steps;

        MockSample(int timestamp, ActivityKind kind, int steps) {
            this.timestamp = timestamp;
            this.kind = kind;
            this.steps = steps;
        }

        @Override public int getTimestamp() { return timestamp; }
        @Override public ActivityKind getKind() { return kind; }
        @Override public int getSteps() { return steps; }
        @Override public SampleProvider<?> getProvider() { return null; }
        @Override public int getRawKind() { return kind.getCode(); }
        @Override public int getRawIntensity() { return NOT_MEASURED; }
        @Override public float getIntensity() { return 0; }
        @Override public int getDistanceCm() { return NOT_MEASURED; }
        @Override public int getActiveCalories() { return NOT_MEASURED; }
        @Override public int getHeartRate() { return NOT_MEASURED; }
        @Override public void setHeartRate(int value) {}
    }
}
