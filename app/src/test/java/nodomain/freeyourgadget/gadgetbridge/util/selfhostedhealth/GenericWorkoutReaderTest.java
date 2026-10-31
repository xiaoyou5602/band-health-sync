package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import android.database.Cursor;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser;
import static nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.*;
import static org.junit.Assert.*;

/** Only synthetic summaries; no device, filesystem or network access. */
public class GenericWorkoutReaderTest {
    private static final long START = 981162000L;
    private static final long NOW = START + 5000;

    private static BaseActivitySummary summary(ActivitySummaryData data) {
        BaseActivitySummary s = new BaseActivitySummary();
        s.setId(42L);
        s.setDeviceId(7L);
        s.setUserId(1L);
        s.setActivityKind(ActivityKind.CYCLING.getCode());
        s.setStartTime(new Date(START * 1000));
        s.setEndTime(new Date((START + 2700) * 1000));
        s.setSummaryData(data == null ? null : data.toJson());
        return s;
    }

    private static WorkoutSummaryInput read(BaseActivitySummary s) {
        return GenericWorkoutReader.decode(s, null, NOW);
    }

    @Test public void commonMetricsAndUnitsAreProjected() {
        ActivitySummaryData d = new ActivitySummaryData();
        d.add(ACTIVE_SECONDS, 1800500, UNIT_MILLISECONDS);
        d.add(DISTANCE_METERS, 6.789, UNIT_KILOMETERS);
        d.add(CALORIES_BURNT, 123, UNIT_KCAL);
        d.add(HR_AVG, 111, UNIT_BPM);
        WorkoutSummaryInput w = read(summary(d));
        assertEquals(1800, w.getActiveSeconds());
        assertEquals(Long.valueOf(2700), w.getTotalSeconds());
        assertEquals(6789, w.getDistanceMeters(), 0.000001);
        assertEquals(123, w.getActiveCalories(), 0);
        assertEquals(Integer.valueOf(111), w.getAverageHeartRate());
        assertEquals(0, w.getRawType());
    }

    @Test public void missingMetricsDoNotInventCaloriesOrHeartRate() {
        ActivitySummaryData d = new ActivitySummaryData();
        d.add(CALORIES_TOTAL, 555, UNIT_KCAL);
        d.add(DISTANCE_METERS, 20, "unknown-unit");
        WorkoutSummaryInput w = read(summary(d));
        assertEquals(2700, w.getActiveSeconds()); // documented elapsed fallback
        assertNull(w.getActiveCalories());
        assertNull(w.getDistanceMeters());
        assertNull(w.getAverageHeartRate());
    }

    @Test public void zeroActiveTimeIsPreserved() {
        ActivitySummaryData d = new ActivitySummaryData();
        d.add(ACTIVE_SECONDS, 0, UNIT_SECONDS, true);
        assertEquals(0, read(summary(d)).getActiveSeconds());
    }

    @Test public void identifiersAreStableAndSeparateFromLegacyHuawei() {
        BaseActivitySummary s = summary(null);
        String id = SelfHostedWorkoutPayload.identity(read(s));
        assertEquals("gbw1:500000000000042:" + START + ":0:" + ActivityKind.CYCLING.getCode(), id);
        assertEquals(id, SelfHostedWorkoutPayload.identity(read(s)));
        s.setId(43L);
        assertNotEquals(id, SelfHostedWorkoutPayload.identity(read(s)));
        assertNotEquals(id, SelfHostedWorkoutPayload.identity(SelfHostedWorkoutPayloadTest.cycling()));
    }

    @Test public void rawSummaryIsParsedWithoutDetailsAndWithoutSaving() {
        BaseActivitySummary s = summary(null);
        s.setRawSummaryData(new byte[]{1, 2});
        ActivitySummaryParser parser = (detached, details) -> {
            assertFalse(details);
            assertNull(detached.getGpxTrack());
            assertNull(detached.getRawDetailsPath());
            assertNull(detached.getName());
            ActivitySummaryData d = new ActivitySummaryData();
            d.add(ACTIVE_SECONDS, 1234, UNIT_SECONDS);
            detached.setSummaryData(d.toJson());
            return detached;
        };
        assertEquals(1234, GenericWorkoutReader.decode(s, parser, NOW).getActiveSeconds());
    }

    @Test public void storedJsonDoesNotRequireAParser() {
        BaseActivitySummary s = summary(new ActivitySummaryData());
        s.setRawSummaryData(new byte[]{1});
        assertEquals(2700, GenericWorkoutReader.decode(s, (ignored, details) -> {
            throw new AssertionError("Stored JSON should be sufficient");
        }, NOW).getActiveSeconds());
    }

    @Test public void sleepAndUnfinishedWorkoutsAreRejected() {
        BaseActivitySummary s = summary(null);
        s.setActivityKind(ActivityKind.DEEP_SLEEP.getCode());
        assertThrows(IllegalArgumentException.class, () -> read(s));
        s.setActivityKind(ActivityKind.CYCLING.getCode());
        s.setEndTime(new Date((NOW + 1) * 1000));
        assertThrows(IllegalArgumentException.class, () -> read(s));
        s.setEndTime(s.getStartTime());
        assertThrows(IllegalArgumentException.class, () -> read(s));
    }

    @Test public void badOptionalHeartRateDoesNotLoseWorkout() {
        ActivitySummaryData d = new ActivitySummaryData();
        d.add(HR_AVG, 255, UNIT_BPM);
        d.add(HR_MIN, 150, UNIT_BPM);
        d.add(HR_MAX, 100, UNIT_BPM);
        WorkoutSummaryInput w = read(summary(d));
        assertNull(w.getAverageHeartRate());
        assertNull(w.getMinHeartRate());
        assertNull(w.getMaxHeartRate());
    }

    @Test public void malformedRowDoesNotLoseValidRowsOrLegacyPayload() throws Exception {
        Object[][] rows = {
            {1L, 128, START*1000, (START+2700)*1000, "{", null, 7L, 1L},
            {2L, 128, START*1000, (START+2700)*1000, "{}", null, 7L, 1L}
        };
        int[] position = {-1};
        Cursor cursor = (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class},
            (proxy, method, args) -> {
                if (method.getName().equals("moveToNext")) return ++position[0] < rows.length;
                Object value = rows[position[0]][(Integer) args[0]];
                switch (method.getName()) {
                    case "isNull": return value == null;
                    case "getInt": return ((Number) value).intValue();
                    case "getLong": return ((Number) value).longValue();
                    case "getString": case "getBlob": return value;
                    default: throw new AssertionError(method.getName());
                }
            });
        List<WorkoutSummaryInput> result = GenericWorkoutReader.readRows(cursor, null, NOW);
        assertEquals(1, result.size());
        SelfHostedHealthPayloadSet base = SelfHostedWorkoutSyncTest.legacy();
        SelfHostedHealthPayloadSet enriched = SelfHostedWorkoutSync.attach(base, () -> result,
            SelfHostedWorkoutSyncTest.ZONE, NOW, true);
        SelfHostedWorkoutSyncTest.assertLegacy(base, enriched);
        assertEquals(14, enriched.getDays().get(0).getBody().getJSONArray("workouts").getJSONObject(0).length());
    }
}
