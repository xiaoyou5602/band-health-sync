package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class SelfHostedWorkoutPayloadTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long NOW = Instant.parse("2001-02-04T00:00:00Z").getEpochSecond();
    private static final long START = Instant.parse("2001-02-03T01:00:00Z").getEpochSecond();
    // SYNTHETIC: independently constructed constants; no observed record or shifted real timeline.
    public static WorkoutSummaryInput cycling() {
        return new WorkoutSummaryInput(42, 3, 128, START, START+2700L, 1800L, 2400L, 6789.0, 123.0, 111, 77, 155);
    }
    private static SelfHostedHealthPayloadSet empty() { return new SelfHostedHealthPayloadSet(Collections.emptyList(), 0L); }
    private static JSONObject wire(WorkoutSummaryInput w) throws JSONException {
        return SelfHostedWorkoutPayload.attach(empty(), Collections.singletonList(w), ZONE, NOW)
            .getDays().get(0).getBody().getJSONArray("workouts").getJSONObject(0);
    }
    @Test public void syntheticCyclingHasExactSummaryAndNoForbiddenFields() throws JSONException {
        JSONObject w = wire(cycling());
        assertEquals("gbw1:42:"+START+":3:128", w.getString("id"));
        assertEquals(1800L, w.getLong("active_seconds"));
        assertEquals(2400L, w.getLong("total_seconds"));
        assertEquals(123.0, w.getDouble("active_calories"),0);
        assertEquals(2700L, cycling().getEndSeconds()-cycling().getStartSeconds());
        assertEquals(6789.0,w.getDouble("distance_meters"),0);
        assertEquals(111,w.getInt("average_heart_rate"));
        assertEquals(77,w.getInt("min_heart_rate")); assertEquals(155,w.getInt("max_heart_rate"));
        assertTrue(w.getString("start_time").endsWith("+08:00"));
        assertEquals(14,w.length());
        for(String forbidden:Arrays.asList("gps","route","latitude","longitude","device_address","user_id","name","heart_rate_samples","pace","cadence"))
            assertFalse(w.has(forbidden));
    }
    @Test public void emptyWorkoutsPreserveLegacyInstanceAndBytes() throws JSONException {
        JSONObject body = new JSONObject("{\"date\":\"2001-02-03\",\"steps\":{\"total\":4321},\"heart_rate\":[{\"timestamp\":\"2001-02-03T09:00:00+08:00\",\"value\":81}],\"sleep\":[{\"session_start_time\":\"2001-02-03T00:00:00+08:00\",\"session_end_time\":\"2001-02-03T06:00:00+08:00\",\"duration_seconds\":21600,\"stages\":[]}]}");
        SelfHostedHealthPayloadSet original = new SelfHostedHealthPayloadSet(
            Collections.singletonList(new SelfHostedHealthDay("2001-02-03", body)), 123L);
        String bytes=body.toString();
        assertSame(original, SelfHostedWorkoutPayload.attach(original, Collections.emptyList(), ZONE, NOW));
        SelfHostedHealthPayloadSet attached=SelfHostedWorkoutPayload.attach(original,Collections.singletonList(cycling()),ZONE,NOW);
        assertEquals(bytes,body.toString());
        JSONObject after=attached.getDays().get(0).getBody();
        for(String key:Arrays.asList("steps","heart_rate","sleep")) assertEquals(body.get(key).toString(),after.get(key).toString());
        assertEquals(123L,attached.getSleepUploadedThrough());
    }
    @Test public void identitySurvivesEnrichmentAndNullableIsNotZero() throws JSONException {
        WorkoutSummaryInput sparse=new WorkoutSummaryInput(42,3,128,START,START+2700L,1800L,
            null,null,0.0,null,null,null);
        JSONObject w=wire(sparse);
        assertEquals(wire(cycling()).getString("id"),w.getString("id"));
        assertTrue(w.isNull("distance_meters")); assertTrue(w.isNull("total_seconds"));
        assertTrue(w.isNull("average_heart_rate")); assertEquals(0.0,w.getDouble("active_calories"),0);
        assertEquals(w.toString(),wire(sparse).toString());
    }
    @Test public void midnightAttributionAndActivityCodesArePreserved() throws JSONException {
        long start=Instant.parse("2001-02-03T15:50:00Z").getEpochSecond();
        for(int[] code: new int[][]{{1,16},{5,67109040},{2,32},{13,67108882},{140,2097152},{255,512},{254,0}}) {
            WorkoutSummaryInput row=new WorkoutSummaryInput(2,code[0],code[1],start,start+1800,1700L,
                1750L,null,null,null,null,null);
            SelfHostedHealthPayloadSet result=SelfHostedWorkoutPayload.attach(empty(),Collections.singletonList(row),ZONE,NOW);
            assertEquals(1,result.getDays().size()); assertEquals("2001-02-03",result.getDays().get(0).getDate());
            assertEquals(code[0],wire(row).getInt("raw_type")); assertEquals(code[1],wire(row).getInt("activity_kind"));
        }
    }
    @Test public void duplicateAndConflictingIdentityKeepFirstValidRecord() throws Exception {
        WorkoutSummaryInput conflict=new WorkoutSummaryInput(42,3,128,START,START+2700L,1700L,
            null,null,null,null,null,null);
        for(WorkoutSummaryInput duplicate:Arrays.asList(cycling(),conflict)) {
            org.json.JSONArray rows=SelfHostedWorkoutPayload.attach(empty(),Arrays.asList(cycling(),duplicate),ZONE,NOW)
                .getDays().get(0).getBody().getJSONArray("workouts");
            assertEquals(1,rows.length());
            assertEquals(wire(cycling()).toString(),rows.getJSONObject(0).toString());
        }
    }
    @Test public void invalidDurationDoesNotDiscardValidOrLegacyData() throws Exception {
        WorkoutSummaryInput invalid=new WorkoutSummaryInput(42,3,128,START,START+2700L,2701L,
            null,null,null,null,null,null);
        SelfHostedHealthPayloadSet base=SelfHostedWorkoutSyncTest.legacy();
        for(java.util.List<WorkoutSummaryInput> rows:Arrays.asList(Arrays.asList(invalid,cycling()),Arrays.asList(cycling(),invalid))) {
            SelfHostedHealthPayloadSet result=SelfHostedWorkoutPayload.attach(base,rows,ZONE,NOW);
            org.json.JSONArray workouts=result.getDays().get(0).getBody().getJSONArray("workouts");
            assertEquals(1,workouts.length());
            assertEquals(wire(cycling()).toString(),workouts.getJSONObject(0).toString());
            SelfHostedWorkoutSyncTest.assertLegacy(base,result);
        }
        assertSame(base,SelfHostedWorkoutPayload.attach(base,Arrays.asList(invalid,invalid),ZONE,NOW));
        assertFalse(base.getDays().get(0).getBody().has("workouts"));
    }
    @Test public void invalidTimestampAndNumericFieldsAreRecordLocal() throws Exception {
        for(WorkoutSummaryInput invalid:Arrays.asList(
            new WorkoutSummaryInput(1,3,128,Long.MIN_VALUE,Long.MAX_VALUE,1L,null,null,null,null,null,null),
            new WorkoutSummaryInput(1,3,128,Long.MIN_VALUE,Long.MIN_VALUE+2,1L,null,null,null,null,null,null),
            new WorkoutSummaryInput(1,3,128,START,START+2700,1800L,1700L,null,null,null,null,null),
            new WorkoutSummaryInput(1,3,128,START,START+2700,1800L,null,Double.NaN,null,null,null,null),
            new WorkoutSummaryInput(1,3,128,START,START+2700,1800L,null,null,null,255,null,null))) {
            org.json.JSONArray result=SelfHostedWorkoutPayload.attach(empty(),Arrays.asList(invalid,cycling()),ZONE,NOW)
                .getDays().get(0).getBody().getJSONArray("workouts");
            assertEquals(1,result.length());
            assertEquals(wire(cycling()).toString(),result.getJSONObject(0).toString());
        }
    }
    /** Emits only the explicitly synthetic fixture; never uploads anything. */
    public static void main(String[] args) {
        System.out.println(SelfHostedWorkoutPayload.attach(empty(),Collections.singletonList(cycling()),ZONE,NOW).getDays().get(0).getBody());
    }
}
