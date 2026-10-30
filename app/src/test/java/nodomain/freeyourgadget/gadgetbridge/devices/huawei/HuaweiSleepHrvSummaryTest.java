package nodomain.freeyourgadget.gadgetbridge.devices.huawei;

import org.junit.Test;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiSleepStatsSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;
import static org.junit.Assert.*;

public class HuaweiSleepHrvSummaryTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long START = Instant.parse("2001-02-02T15:00:00Z").toEpochMilli();
    private static final long END = START + 8L * 3600000;
    private HuaweiSleepStatsSample sleep(int average) {
        HuaweiSleepStatsSample sample = new HuaweiSleepStatsSample();
        sample.setTimestamp(START);
        sample.setWakeupTime(END);
        sample.setAvgHrv(average);
        sample.setMinHrvBaseline(45);
        sample.setMaxHrvBaseline(84);
        return sample;
    }
    @Test public void nightlySummaryAppearsOnWakeDateWithoutInventingRawStatistics() {
        List<HrvSummarySample> values = HuaweiHrvSummarySampleProvider.merge(Collections.emptyList(),
                Arrays.asList(sleep(60), sleep(-1)), END - 3600000, END, ZONE);
        assertEquals(1, values.size());
        HrvSummarySample value = values.get(0);
        assertEquals(END, value.getTimestamp());
        assertEquals(Integer.valueOf(60), value.getLastNightAverage());
        assertEquals(Integer.valueOf(45), value.getBaselineBalancedLower());
        assertNull(value.getWeeklyAverage());
        assertNull(value.getLastNight5MinHigh());
        assertEquals(HrvSummarySample.Status.NONE, value.getStatus());
        assertTrue(HuaweiHrvSummarySampleProvider.merge(Collections.emptyList(),
                Collections.singletonList(sleep(60)), START, END - 1, ZONE).isEmpty());
    }
    @Test public void measuredSummaryWinsAndMissingBaselinesStayNull() {
        HrvSummarySample measured = new ComputedHrvSummarySampleProvider.ComputedHrvSummarySample(
                END, 70, 71, 90, 40, 50, 80, HrvSummarySample.Status.BALANCED);
        assertSame(measured, HuaweiHrvSummarySampleProvider.merge(Collections.singletonList(measured),
                Collections.singletonList(sleep(60)), START, END, ZONE).get(0));
        HuaweiSleepStatsSample missing = sleep(60);
        missing.setMinHrvBaseline(-1);
        missing.setMaxHrvBaseline(-1);
        assertNull(new HuaweiHrvSummarySampleProvider.SleepSummary(missing).getBaselineBalancedLower());
    }
}
