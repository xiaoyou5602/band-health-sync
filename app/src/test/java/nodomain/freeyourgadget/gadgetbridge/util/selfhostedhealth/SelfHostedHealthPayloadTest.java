package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

/**
 * Pins the wire format the self-hosted health server ingests. The server merges rather than
 * overwrites, and every rule here exists because a merge behaves badly if the fork gets it wrong.
 */
public class SelfHostedHealthPayloadTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    public void emptyInputProducesNoRequests() {
        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(Collections.emptyList(), ZONE, 0L, ts(2026, 9, 2, 9, 0));
        assertEquals(0, payload.getDays().size());
        assertEquals(0L, payload.getSleepUploadedThrough());
    }

    /** The server keeps the larger step total per day, so a day payload must carry the day total. */
    @Test
    public void stepsAreTotalledPerLocalDay() throws Exception {
        List<ActivitySample> samples = Arrays.asList(
                steps(ts(2026, 9, 1, 8, 0), 100),
                steps(ts(2026, 9, 1, 20, 0), 250),
                steps(ts(2026, 9, 2, 8, 0), 70)
        );

        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(samples, ZONE, 0L, ts(2026, 9, 2, 9, 0));

        assertEquals(2, payload.getDays().size());
        assertEquals("2026-09-01", payload.getDays().get(0).getDate());
        assertEquals(350L, bodyFor(payload, "2026-09-01").getJSONObject("steps").getLong("total"));
        assertEquals(70L, bodyFor(payload, "2026-09-02").getJSONObject("steps").getLong("total"));
    }

    /** The server keeps the last 288 heart rate samples of a day; 5-minute buckets fill a day exactly. */
    @Test
    public void heartRateIsAveragedIntoFiveMinuteBuckets() throws Exception {
        int bucketStart = ts(2026, 9, 2, 8, 0);
        List<ActivitySample> samples = Arrays.asList(
                heartRate(bucketStart + 60, 60),
                heartRate(bucketStart + 120, 70),
                heartRate(bucketStart + 180, 62),
                heartRate(bucketStart + 300, 90)
        );

        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(samples, ZONE, 0L, ts(2026, 9, 2, 9, 0));

        JSONArray heartRate = bodyFor(payload, "2026-09-02").getJSONArray("heart_rate");
        assertEquals(2, heartRate.length());
        assertEquals(64, heartRate.getJSONObject(0).getInt("value")); // (60 + 70 + 62) / 3
        assertEquals("2026-09-02T08:00:00+08:00", heartRate.getJSONObject(0).getString("timestamp"));
        assertEquals(90, heartRate.getJSONObject(1).getInt("value"));
        assertEquals("2026-09-02T08:05:00+08:00", heartRate.getJSONObject(1).getString("timestamp"));
    }

    @Test
    public void heartRateDoesNotExceedTheServerSampleCap() throws Exception {
        List<ActivitySample> samples = new ArrayList<>();
        int dayStart = ts(2026, 9, 2, 0, 0);
        for (int minute = 0; minute < 24 * 60; minute++) {
            samples.add(heartRate(dayStart + minute * 60, 60 + (minute % 20)));
        }

        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(samples, ZONE, 0L, ts(2026, 9, 3, 1, 0));

        assertEquals(288, bodyFor(payload, "2026-09-02").getJSONArray("heart_rate").length());
    }

    /** 0 means "not measured" and 255 is Gadgetbridge's bad-measurement sentinel; both would land
     *  inside the server's accepted range and skew the daily average. */
    @Test
    public void invalidHeartRateValuesAreDropped() {
        List<ActivitySample> samples = Arrays.asList(
                heartRate(ts(2026, 9, 2, 8, 0), 0),
                heartRate(ts(2026, 9, 2, 8, 30), 255),
                heartRate(ts(2026, 9, 2, 9, 0), -1)
        );

        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(samples, ZONE, 0L, ts(2026, 9, 2, 10, 0));

        assertEquals(0, payload.getDays().size());
    }

    /** A night is one record filed under the day the sleeper woke up, matching how the app itself
     *  attributes sleep. */
    @Test
    public void sleepIsFiledUnderTheWakeDate() throws Exception {
        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(night(ts(2026, 9, 2, 9, 0)), ZONE, 0L, ts(2026, 9, 2, 9, 5));

        JSONObject body = bodyFor(payload, "2026-09-02");
        JSONArray sessions = body.getJSONArray("sleep");
        assertEquals(1, sessions.length());

        JSONObject session = sessions.getJSONObject(0);
        assertEquals("2026-09-01T23:00:00+08:00", session.getString("session_start_time"));
        assertEquals("2026-09-02T02:00:01+08:00", session.getString("session_end_time"));
        // light 1800 + deep 3600 + rem 1800 + trailing light 1801; the awake phase is excluded,
        // the same way the app's own totals treat it.
        assertEquals(9001L, session.getLong("duration_seconds"));
        assertEquals(ts(2026, 9, 2, 2, 0) + 1, payload.getSleepUploadedThrough());

        JSONArray stages = session.getJSONArray("stages");
        assertEquals(5, stages.length());
        assertEquals("light", stages.getJSONObject(0).getString("stage"));
        assertEquals(1800L, stages.getJSONObject(0).getLong("duration_seconds"));
        assertEquals("deep", stages.getJSONObject(1).getString("stage"));
        assertEquals(3600L, stages.getJSONObject(1).getLong("duration_seconds"));
        assertEquals("rem", stages.getJSONObject(2).getString("stage"));
        assertEquals("awake", stages.getJSONObject(3).getString("stage"));
        assertEquals("light", stages.getJSONObject(4).getString("stage"));
        assertEquals("2026-09-01T23:00:00+08:00", stages.getJSONObject(0).getString("start_time"));
    }

    /**
     * A night whose newest data still sits inside the settle window may not be finished (the fetch
     * could have stopped mid-session), so it is held back rather than posted as a half-night.
     */
    @Test
    public void sleepThatMayStillGrowIsHeldBack() {
        // Newest sample sits ~5 minutes past the session end, inside the 10-minute settle window.
        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(night(ts(2026, 9, 2, 2, 5)), ZONE, 0L, ts(2026, 9, 2, 2, 15));

        // The day is still posted for its steps; only the unsettled night is withheld.
        assertFalse(bodyFor(payload, "2026-09-02").has("sleep"));
        assertEquals(0L, payload.getSleepUploadedThrough());
    }

    @Test
    public void sleepPastTenMinuteSettleWindowIsUploaded() {
        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(night(ts(2026, 9, 2, 2, 11)), ZONE, 0L, ts(2026, 9, 2, 2, 15));

        assertTrue(bodyFor(payload, "2026-09-02").has("sleep"));
    }

    @Test
    public void sleepAlreadyUploadedIsNotSentAgain() {
        long alreadyUploaded = ts(2026, 9, 2, 2, 0) + 1;
        SelfHostedHealthPayloadSet payload = SelfHostedHealthPayload.build(
                night(ts(2026, 9, 2, 9, 0)), ZONE, alreadyUploaded, ts(2026, 9, 2, 9, 5));

        assertFalse(bodyFor(payload, "2026-09-02").has("sleep"));
        assertEquals(0L, payload.getSleepUploadedThrough());
    }

    /** The server files records by calendar day in its own zone, so a naive local time would be
     *  read as UTC and could land on the wrong day. */
    @Test
    public void timestampsCarryAnExplicitOffset() throws Exception {
        List<ActivitySample> samples = Collections.singletonList(heartRate(ts(2026, 9, 2, 8, 0), 60));
        SelfHostedHealthPayloadSet payload =
                SelfHostedHealthPayload.build(samples, ZONE, 0L, ts(2026, 9, 2, 9, 0));

        String timestamp = bodyFor(payload, "2026-09-02")
                .getJSONArray("heart_rate").getJSONObject(0).getString("timestamp");
        assertTrue(timestamp, timestamp.endsWith("+08:00"));
    }

    /**
     * One night: 23:00 light, an hour of deep, half an hour of REM, half an hour awake, an hour of
     * light, then morning activity that both closes the session and moves the data horizon.
     */
    private static List<ActivitySample> night(int lastSampleTs) {
        return Arrays.asList(
                sleep(ts(2026, 9, 1, 23, 0), ActivityKind.LIGHT_SLEEP),
                sleep(ts(2026, 9, 1, 23, 30), ActivityKind.DEEP_SLEEP),
                sleep(ts(2026, 9, 2, 0, 0), ActivityKind.DEEP_SLEEP),
                sleep(ts(2026, 9, 2, 0, 30), ActivityKind.REM_SLEEP),
                sleep(ts(2026, 9, 2, 1, 0), ActivityKind.AWAKE_SLEEP),
                sleep(ts(2026, 9, 2, 1, 30), ActivityKind.LIGHT_SLEEP),
                sleep(ts(2026, 9, 2, 2, 0), ActivityKind.LIGHT_SLEEP),
                steps(lastSampleTs, 400)
        );
    }

    private static JSONObject bodyFor(SelfHostedHealthPayloadSet payload, String date) {
        for (SelfHostedHealthDay day : payload.getDays()) {
            if (day.getDate().equals(date)) {
                return day.getBody();
            }
        }
        throw new AssertionError("No payload for " + date);
    }

    private static int ts(int year, int month, int day, int hour, int minute) {
        return (int) ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toEpochSecond();
    }

    private static MockSample sleep(int timestamp, ActivityKind kind) {
        return new MockSample(timestamp, kind, 0, ActivitySample.NOT_MEASURED);
    }

    private static MockSample steps(int timestamp, int steps) {
        return new MockSample(timestamp, ActivityKind.ACTIVITY, steps, ActivitySample.NOT_MEASURED);
    }

    private static MockSample heartRate(int timestamp, int heartRate) {
        return new MockSample(timestamp, ActivityKind.ACTIVITY, 0, heartRate);
    }

    private static class MockSample implements ActivitySample {
        private final int timestamp;
        private final ActivityKind kind;
        private final int steps;
        private int heartRate;

        MockSample(int timestamp, ActivityKind kind, int steps, int heartRate) {
            this.timestamp = timestamp;
            this.kind = kind;
            this.steps = steps;
            this.heartRate = heartRate;
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
        @Override public int getHeartRate() { return heartRate; }
        @Override public void setHeartRate(int value) { this.heartRate = value; }
    }
}
