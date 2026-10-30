package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class SelfHostedSleepHrvPayloadTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long START = Instant.parse("2001-02-02T15:00:00Z").toEpochMilli();
    private static final long END = START + 8L * 3600000;
    private static final long CAPTURED = END / 1000 + 3600;
    private static SleepHrvInput row(int average, int lower, int upper) {
        return new SleepHrvInput(START, END, average, lower, upper);
    }
    @Test public void backfillsWakeDateWithoutMovingSleepCursorOrChangingOrdinaryData() throws Exception {
        JSONObject original = new JSONObject("{\"date\":\"2001-02-03\",\"steps\":{\"total\":100}}");
        SelfHostedHealthPayloadSet base = new SelfHostedHealthPayloadSet(Collections.singletonList(
                new SelfHostedHealthDay("2001-02-03", original)), 123L);
        SelfHostedHealthPayloadSet result = SelfHostedSleepHrvPayload.attach(base,
                Collections.singletonList(row(60, 45, 84)), ZONE, CAPTURED);
        assertEquals(123L, result.getSleepUploadedThrough());
        assertFalse(original.has("sleep_hrv"));
        assertEquals(1, result.getDays().size());
        assertEquals("2001-02-03", result.getDays().get(0).getDate());
        JSONObject body = result.getDays().get(0).getBody();
        assertEquals(100, body.getJSONObject("steps").getInt("total"));
        JSONObject hrv = body.getJSONArray("sleep_hrv").getJSONObject(0);
        assertEquals(60, hrv.getInt("average_ms"));
        assertEquals("huawei_sleep_statistics", hrv.getString("source"));
        assertTrue(hrv.getString("sleep_start").startsWith("2001-02-02T23:00"));
        assertTrue(hrv.getString("sleep_end").startsWith("2001-02-03T07:00"));
        assertEquals(7, hrv.length());
    }
    @Test public void missingBaselineIsNullAndMalformedRowsAreIgnored() throws Exception {
        SelfHostedHealthPayloadSet base = new SelfHostedHealthPayloadSet(Collections.emptyList(), 0L);
        SelfHostedHealthPayloadSet result = SelfHostedSleepHrvPayload.attach(base, Arrays.asList(
                row(60, -1, -1), row(0, 45, 84), row(201, 45, 84),
                new SleepHrvInput(END, START, 60, 45, 84)), ZONE, CAPTURED);
        JSONObject hrv = result.getDays().get(0).getBody().getJSONArray("sleep_hrv").getJSONObject(0);
        assertTrue(hrv.isNull("baseline_lower_ms"));
        assertTrue(hrv.isNull("baseline_upper_ms"));
        assertEquals(60, hrv.getInt("average_ms"));
        assertTrue(SelfHostedSleepHrvPayload.attach(base, Collections.singletonList(row(60,45,84)),
                ZONE, START / 1000).getDays().isEmpty());
    }
}
