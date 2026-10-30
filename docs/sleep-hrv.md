# Huawei sleep HRV support

This branch adds Huawei nightly HRV summaries to the HRV screen, dashboard and
self-hosted health uploads. It is based on release `0.92.8`, commit
`ec4be43dab9b5bac82dbc655909bd777de340c0c`. The personal build version is
`0.92.8-sleep-hrv.1`, version code 258. It does not include the separate 0.92.9
release changes.

## Data and display

The existing `HuaweiSleepStatsSample` table already contains sleep-average HRV,
baseline bounds, sleep onset and wake-up time. No database migration or Bluetooth
protocol change is required. Summaries belong to the wake-up date. Existing
measurement-derived summaries take priority for dates that have them.

Nightly summaries do not supply intranight measurements, a 5-minute maximum or a
recovery status. Those fields remain unavailable. The dashboard labels the
fallback value as the sleep HRV average, and the HRV screen explains its source.

## Upload contract

Daily payloads gain an optional `sleep_hrv` array. This is synthetic example data:

```json
{
  "date": "2001-02-03",
  "sleep_hrv": [{
    "source": "huawei_sleep_statistics",
    "sleep_start": "2001-02-02T23:00:00+08:00",
    "sleep_end": "2001-02-03T07:00:00+08:00",
    "average_ms": 60,
    "baseline_lower_ms": 45,
    "baseline_upper_ms": 84,
    "captured_at": "2001-02-03T09:00:00+08:00"
  }]
}
```

Missing or invalid baselines use JSON null. The first successful upload backfills
up to 31 days within the configured initial-sync range, independently of the
ordinary sleep-upload cursor. Subsequent uploads use the existing look-back
window. A failed upload does not advance the HRV backfill flag. Failure to read
the optional HRV data preserves the existing health payload.

The self-hosted server must support `sleep_hrv` before installing this build.
Server support is a separate change to `xiaoyou5602/health-mcp`; old server
versions may accept requests while ignoring the new field.

## Build and validation

Use Java 17 and the SDK/build-tools versions specified by the project (37).
Configure your own release signing through the existing project signing setup;
keep signing keys and passwords outside version control.

```text
gradlew :app:assembleMainlineRelease
gradlew :app:testMainlineDebugUnitTest --tests "*SelfHosted*" --tests "*HuaweiSleepHrv*"
```

After dependencies are cached, `--offline` also avoids remote dependency-version
lookups during release lint checks. It does not disable the release checks.

Validation completed for this change:

- Release build and release checks passed.
- All 46 selected Android tests passed, including four new HRV regression tests.
- Release signing, package identity, version code and non-debuggable flag checked.
- Device-side screenshots and server/MCP reads confirmed the nightly values and
  successful manual historical backfill. Background HRV sync still requires
  longer observation.

Tests use synthetic values and contain no device identifiers or personal records.

## Moving from an author-signed build

A build signed with your own key cannot replace a differently signed installation.
Export a fresh complete ZIP backup and store it outside the app's own directory
before uninstalling. Preserve the old APK as well. Install the personal build,
restore the ZIP and grant required permissions again. Do not factory-reset the
band or remove the system Bluetooth pairing as part of this migration.

Keep the same personal signing key for future updates.
