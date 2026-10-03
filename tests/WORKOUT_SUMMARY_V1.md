# Optional self-hosted workout summaries

Based on public v0.92.7 (`028de2552a398e383d096b629a73356507a19890`). This change adds summary-only workout serialization and worker integration without changing acquisition, Bluetooth, Health Connect or the legacy payload builder. The self-hosted settings page has an independent **Upload workout summaries** switch, OFF by default. Only explicit opt-in reads workout storage or attaches `workouts[]`; the existing health upload switch and legacy behavior are unchanged.

The reader uses the selected Huawei device only as an internal database selector. It joins existing workout summaries to BaseActivitySummary normalization by device/start, selects explicit columns and reads numeric entries through ActivitySummaryData. It does not open track files.

The wire allowlist contains `id`, `raw_type`, `activity_kind`, `start_time`, `end_time`, `timezone`, `captured_at`, `active_seconds`, `total_seconds`, `distance_meters`, `active_calories`, `average_heart_rate`, `min_heart_rate` and `max_heart_rate`. Optional unknown values remain null. GPS, routes, account/device identifiers, raw detail samples and arbitrary summary entries are excluded.

Identity is `gbw1:<WORKOUT_ID>:<start seconds>:<raw type>:<ActivityKind>`. Active duration and elapsed duration are distinct; total duration is nullable. A whole workout belongs to its local start date. No-workout payloads retain the original instance and bytes. The existing upload window remains unchanged: only ended workouts starting in that window qualify. Historical backfill, database re-index reconciliation and multi-device identity routing are outside this change. Malformed or unsupported summaries are skipped individually. Valid summaries continue, including after a summary parse failure. Duplicate/conflicting identities keep the first valid record in start/id order; malformed records cannot reserve an identity. At most 128 summaries per local day are attached; excess records are skipped. A workout-subsystem read or enrichment failure retains the exact legacy payload and sleep cursor. Diagnostics contain only fixed text and exception class names, never row values or exception messages. Legacy packaging and upload errors keep their original handling outside this optional enrichment boundary.

## Synthetic contract fixtures

All workout test data is **synthetic**, independently constructed for contract testing. No fixture is an observed workout, a renamed record or a time-shifted real sample. The fabricated calendar, identifiers, durations and numeric summaries have no device provenance. Public raw-type and ActivityKind constants retain their protocol meaning.

Tests cover exact summary shape and privacy allowlisting; unchanged legacy instance/bytes; stable identity, enrichment, null versus zero and repeatability; local midnight attribution and raw/activity code preservation; duplicate/conflicting identity handling; invalid-duration, timestamp and numeric isolation; default/off reader suppression; explicit opt-in; and workout-subsystem fallback. Android unit tests additionally exercise the real summary parser with synthetic cursors, including single/all malformed records and cursor failure. Active, total and elapsed durations are deliberately different. These tests do not establish real-device activity classification or phone-to-server acceptance.

## Running checks

`tests/workout-wire-jvm.ps1 -CompilerClasspath <Kotlin compiler jars> -TestClasspath <stdlib;JUnit;Hamcrest;org.json;SLF4J API jars> -OutputDirectory <scratch directory>` compiles the real serializer, opt-in enrichment boundary and DTO declarations extracted from the unchanged legacy source. It emits only synthetic `cycling-wire.json`. Paths and existing dependencies are caller-supplied.

Android regression command: `:app:testMainlineReleaseUnitTest --tests '*SelfHosted*' --offline`. The standalone workout cases overlap the Android suite and must not be added to that suite's count. JVM tests do not exercise Android Reader/Worker integration. Test results belong to the exact submitted candidate and are reported in its PR validation section.

The inherited full lint result is failing (6 errors, 1,341 warnings, 2 hints in the recorded base comparison). This change does not claim full lint passes. No signing, installation, migration or production acceptance is implied by local verification.
