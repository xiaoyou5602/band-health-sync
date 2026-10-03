package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;
import static org.junit.Assert.*;

/** All records and legacy values are independently constructed synthetic fixtures. */
public class SelfHostedWorkoutSyncTest {
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final long NOW = Instant.parse("2001-02-04T00:00:00Z").getEpochSecond();
    static SelfHostedHealthPayloadSet legacy() throws Exception {
        return new SelfHostedHealthPayloadSet(Collections.singletonList(new SelfHostedHealthDay(
            "2001-02-03", new JSONObject("{\"date\":\"2001-02-03\",\"steps\":{\"total\":4321},\"heart_rate\":[{\"value\":81}],\"sleep\":[{\"duration_seconds\":21600}]}"))), 123L);
    }
    static void assertLegacy(SelfHostedHealthPayloadSet before, SelfHostedHealthPayloadSet after) throws Exception {
        assertEquals(before.getSleepUploadedThrough(), after.getSleepUploadedThrough());
        for (String key : Arrays.asList("steps", "heart_rate", "sleep"))
            assertEquals(before.getDays().get(0).getBody().get(key).toString(), after.getDays().get(0).getBody().get(key).toString());
        assertFalse(before.getDays().get(0).getBody().has("workouts"));
    }
    @Test public void defaultAndExplicitOffNeverReadWorkouts() throws Exception {
        SelfHostedHealthPayloadSet base = legacy();
        Supplier<java.util.List<WorkoutSummaryInput>> forbidden = () -> { throw new AssertionError("workout reader invoked while OFF"); };
        assertSame(base, SelfHostedWorkoutSync.attach(base, forbidden, ZONE, NOW));
        assertSame(base, SelfHostedWorkoutSync.attach(base, forbidden, ZONE, NOW, false));
        assertEquals(123L, base.getSleepUploadedThrough());
        assertFalse(base.getDays().get(0).getBody().has("workouts"));
    }
    @Test public void explicitOnEnrichesWithoutChangingLegacyOrCursor() throws Exception {
        SelfHostedHealthPayloadSet base = legacy();
        SelfHostedHealthPayloadSet result = SelfHostedWorkoutSync.attach(base,
            () -> Collections.singletonList(SelfHostedWorkoutPayloadTest.cycling()), ZONE, NOW, true);
        assertEquals(1, result.getDays().get(0).getBody().getJSONArray("workouts").length());
        assertLegacy(base, result);
    }
    @Test public void subsystemFailureReturnsExactLegacy() throws Exception {
        SelfHostedHealthPayloadSet base = legacy();
        String bytes = base.getDays().get(0).getBody().toString();
        assertSame(base, SelfHostedWorkoutSync.attach(base,
            () -> { throw new IllegalStateException("synthetic reader failure"); }, ZONE, NOW, true));
        assertEquals(bytes, base.getDays().get(0).getBody().toString());
        assertLegacy(base, base);
    }
}
