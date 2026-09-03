package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers the pure core of the sync log: the file wrappers just read/write what these produce, so
 * pinning serialize/deserialize/merge/cap here is what keeps the stored history honest.
 */
public class SelfHostedHealthLogTest {

    private SelfHostedHealthLogEntry entry(long ts, boolean success, String payload) {
        return new SelfHostedHealthLogEntry(
                ts, "Band", "https://health.example.com/api/health", "2026-09-03",
                false, 3, success ? 200 : 401, 120L, success,
                success ? null : "HTTP 401: Unauthorized", payload);
    }

    /** A round trip must reproduce every field, including a null message on success. */
    @Test
    public void serializeDeserializeIsLossless() {
        List<SelfHostedHealthLogEntry> original = Arrays.asList(
                entry(300L, true, "{\"date\":\"2026-09-03\"}"),
                entry(200L, false, "{\"date\":\"2026-09-02\"}")
        );

        List<SelfHostedHealthLogEntry> restored =
                SelfHostedHealthLog.deserialize(SelfHostedHealthLog.serialize(original));

        assertEquals(original, restored);
        assertNull(restored.get(0).getMessage());
        assertEquals("HTTP 401: Unauthorized", restored.get(1).getMessage());
    }

    /** Merge orders newest first regardless of the batch's own order. */
    @Test
    public void mergeSortsNewestFirst() {
        List<SelfHostedHealthLogEntry> existing = Collections.singletonList(entry(100L, true, "a"));
        List<SelfHostedHealthLogEntry> batch = Arrays.asList(entry(300L, true, "c"), entry(200L, true, "b"));

        List<SelfHostedHealthLogEntry> merged = SelfHostedHealthLog.merge(existing, batch, 10);

        assertEquals(3, merged.size());
        assertEquals(300L, merged.get(0).getTimestampMs());
        assertEquals(200L, merged.get(1).getTimestampMs());
        assertEquals(100L, merged.get(2).getTimestampMs());
    }

    /** Merge keeps only the newest [max] entries so the file cannot grow without bound. */
    @Test
    public void mergeCapsToMax() {
        List<SelfHostedHealthLogEntry> existing = Arrays.asList(
                entry(1L, true, "x"), entry(2L, true, "x"), entry(3L, true, "x")
        );
        List<SelfHostedHealthLogEntry> batch = Arrays.asList(entry(4L, true, "x"), entry(5L, true, "x"));

        List<SelfHostedHealthLogEntry> merged = SelfHostedHealthLog.merge(existing, batch, 4);

        assertEquals(4, merged.size());
        assertEquals(5L, merged.get(0).getTimestampMs());
        assertEquals(2L, merged.get(3).getTimestampMs());
    }

    /** An oversized payload is truncated; a normal one is left untouched. */
    @Test
    public void capTruncatesLongPayload() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < SelfHostedHealthLog.MAX_PAYLOAD_CHARS + 500; i++) {
            big.append('x');
        }

        SelfHostedHealthLogEntry capped = SelfHostedHealthLog.cap(entry(1L, true, big.toString()));
        assertEquals(SelfHostedHealthLog.MAX_PAYLOAD_CHARS + 2, capped.getPayload().length());

        SelfHostedHealthLogEntry small = entry(1L, true, "{\"date\":\"2026-09-03\"}");
        assertEquals(small.getPayload(), SelfHostedHealthLog.cap(small).getPayload());
    }

    /** Corrupt or absent data reads back as an empty log instead of throwing. */
    @Test
    public void badInputDeserializesToEmpty() {
        assertTrue(SelfHostedHealthLog.deserialize(null).isEmpty());
        assertTrue(SelfHostedHealthLog.deserialize("").isEmpty());
        assertTrue(SelfHostedHealthLog.deserialize("not json at all").isEmpty());
    }
}
