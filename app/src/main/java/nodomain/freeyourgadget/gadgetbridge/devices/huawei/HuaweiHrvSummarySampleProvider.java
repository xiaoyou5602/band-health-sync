package nodomain.freeyourgadget.gadgetbridge.devices.huawei;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiSleepStatsSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;

/** Sleep summaries fill missing days without creating synthetic HRV measurements. */
public class HuaweiHrvSummarySampleProvider extends ComputedHrvSummarySampleProvider {
    private final HuaweiSleepStatsSampleProvider sleepProvider;

    public HuaweiHrvSummarySampleProvider(final GBDevice device, final DaoSession session) {
        super(new HuaweiHrvValueSampleProvider(device, session), device, session);
        sleepProvider = new HuaweiSleepStatsSampleProvider(device, session);
    }

    @Override
    public List<HrvSummarySample> getAllSamples(final long from, final long to) {
        return merge(super.getAllSamples(from, to),
                sleepProvider.getSleepSamples(Math.max(0, from - 2L * 86400000), to),
                from, to, ZoneId.systemDefault());
    }

    @Override
    public HrvSummarySample getLatestSample() {
        return getLatestSample(System.currentTimeMillis());
    }

    @Override
    public HrvSummarySample getLatestSample(final long until) {
        final HrvSummarySample raw = super.getLatestSample(until);
        final List<HrvSummarySample> samples = merge(raw == null ? List.of() : List.of(raw),
                sleepProvider.getSleepSamples(0, until), 0, until, ZoneId.systemDefault());
        return samples.isEmpty() ? null : samples.get(samples.size() - 1);
    }

    @Override
    public HrvSummarySample getFirstSample() {
        final HrvSummarySample raw = super.getFirstSample();
        final List<HrvSummarySample> samples = merge(raw == null ? List.of() : List.of(raw),
                sleepProvider.getSleepSamples(0, System.currentTimeMillis()),
                0, System.currentTimeMillis(), ZoneId.systemDefault());
        return samples.isEmpty() ? null : samples.get(0);
    }

    public static List<HrvSummarySample> merge(final List<HrvSummarySample> measured,
                                              final List<HuaweiSleepStatsSample> sleep,
                                              final long from, final long to, final ZoneId zone) {
        final TreeMap<LocalDate, HrvSummarySample> days = new TreeMap<>();
        for (final HuaweiSleepStatsSample sample : sleep) {
            final long wake = sample.getWakeupTime();
            if (wake < from || wake > to || wake <= sample.getTimestamp()
                    || wake - sample.getTimestamp() > 2L * 86400000
                    || sample.getAvgHrv() <= 0 || sample.getAvgHrv() > 200) {
                continue;
            }
            final LocalDate date = Instant.ofEpochMilli(wake).atZone(zone).toLocalDate();
            final HrvSummarySample previous = days.get(date);
            if (previous == null || previous.getTimestamp() < wake) {
                days.put(date, new SleepSummary(sample));
            }
        }
        // Existing measurement-derived summaries always take precedence for that date.
        for (final HrvSummarySample sample : measured) {
            days.put(Instant.ofEpochMilli(sample.getTimestamp()).atZone(zone).toLocalDate(), sample);
        }
        final List<HrvSummarySample> result = new ArrayList<>(days.values());
        result.sort(Comparator.comparingLong(HrvSummarySample::getTimestamp));
        return result;
    }

    public static class SleepSummary extends ComputedHrvSummarySample {
        public SleepSummary(final HuaweiSleepStatsSample sample) {
            super(sample.getWakeupTime(), null, sample.getAvgHrv(), null, null,
                    validBaseline(sample) ? sample.getMinHrvBaseline() : null,
                    validBaseline(sample) ? sample.getMaxHrvBaseline() : null,
                    HrvSummarySample.Status.NONE);
        }

        private static boolean validBaseline(final HuaweiSleepStatsSample sample) {
            return sample.getMinHrvBaseline() > 0
                    && sample.getMaxHrvBaseline() >= sample.getMinHrvBaseline()
                    && sample.getMaxHrvBaseline() <= 200;
        }
    }
}
