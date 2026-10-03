package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import android.database.Cursor;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.Assert.*;

/** Synthetic in-memory cursor; never opens a database or reads a device. */
public class SelfHostedWorkoutReaderTest {
    private static Object[] row(long id, String summary) {
        WorkoutSummaryInput w = SelfHostedWorkoutPayloadTest.cycling();
        return new Object[]{id, 3L, 128L, w.getStartSeconds()*1000, w.getEndSeconds()*1000,
            summary, 1800L, 2400L, 6789L, 123L};
    }
    private static Cursor cursor(Object[]... rows) {
        int[] position = {-1};
        return (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class},
            (proxy, method, args) -> {
                if (method.getName().equals("moveToNext")) return ++position[0] < rows.length;
                Object value = rows[position[0]][(Integer) args[0]];
                switch (method.getName()) {
                    case "isNull": return value == null;
                    case "getLong": return ((Number) value).longValue();
                    case "getString": return (String) value;
                    default: throw new AssertionError("Unexpected cursor operation");
                }
            });
    }
    @Test public void parseFailureSkipsOnlyItsRowAndPreservesLegacy() throws Exception {
        SelfHostedHealthPayloadSet base = SelfHostedWorkoutSyncTest.legacy();
        List<WorkoutSummaryInput> rows = SelfHostedWorkoutReader.readRows(cursor(row(1,"{"), row(2,"{}")));
        assertEquals(1, rows.size());
        assertEquals(2, rows.get(0).getLocalId());
        SelfHostedHealthPayloadSet result = SelfHostedWorkoutSync.attach(base, () -> rows,
            SelfHostedWorkoutSyncTest.ZONE, SelfHostedWorkoutSyncTest.NOW, true);
        assertEquals(1, result.getDays().get(0).getBody().getJSONArray("workouts").length());
        SelfHostedWorkoutSyncTest.assertLegacy(base, result);
    }
    @Test public void allUnreadableRecordsReturnExactLegacy() throws Exception {
        SelfHostedHealthPayloadSet base = SelfHostedWorkoutSyncTest.legacy();
        assertSame(base, SelfHostedWorkoutSync.attach(base,
            () -> SelfHostedWorkoutReader.readRows(cursor(row(1,"{"), row(2,"{\"x\":{\"type\":\"unsupported-synthetic\"}}"))),
            SelfHostedWorkoutSyncTest.ZONE, SelfHostedWorkoutSyncTest.NOW, true));
        SelfHostedWorkoutSyncTest.assertLegacy(base, base);
    }
    @Test public void cursorFailureIsSubsystemFailureAndLegacySurvives() throws Exception {
        Cursor broken = (Cursor) Proxy.newProxyInstance(Cursor.class.getClassLoader(), new Class<?>[]{Cursor.class},
            (proxy, method, args) -> { throw new IllegalStateException("synthetic cursor failure"); });
        assertThrows(IllegalStateException.class, () -> SelfHostedWorkoutReader.readRows(broken));
        SelfHostedHealthPayloadSet base = SelfHostedWorkoutSyncTest.legacy();
        assertSame(base, SelfHostedWorkoutSync.attach(base, () -> SelfHostedWorkoutReader.readRows(broken),
            SelfHostedWorkoutSyncTest.ZONE, SelfHostedWorkoutSyncTest.NOW, true));
    }
}
