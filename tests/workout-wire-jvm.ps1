param(
  [Parameter(Mandatory=$true)][string]$CompilerClasspath,
  [Parameter(Mandatory=$true)][string]$TestClasspath,
  [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$source=Join-Path $root 'app/src/main/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth'
$testRoot=Join-Path $root 'app/src/test/java/nodomain/freeyourgadget/gadgetbridge/util/selfhostedhealth'
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$output=(Resolve-Path $OutputDirectory).Path
# Compile the real wire class and its actual legacy DTO declarations without loading Android.
# This is a pure JVM contract check, not an APK/Worker/SQLite integration build.
$legacy=Get-Content (Join-Path $source 'SelfHostedHealthPayload.kt') -Raw
$begin=$legacy.IndexOf('data class SelfHostedHealthDay')
$finish=$legacy.IndexOf('/**', $legacy.IndexOf('val sleepUploadedThrough: Long', $begin))
if($begin -lt 0 -or $finish -lt 0){throw 'Legacy DTO declarations changed'}
$dto="package nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth`nimport org.json.JSONObject`n"+$legacy.Substring($begin,$finish-$begin)
Set-Content -LiteralPath (Join-Path $output 'LegacyDtos.kt') -Value $dto -Encoding utf8
& java -cp $CompilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath $TestClasspath -d $output (Join-Path $output 'LegacyDtos.kt') (Join-Path $source 'SelfHostedWorkoutPayload.kt') (Join-Path $source 'SelfHostedWorkoutSync.kt')
if($LASTEXITCODE -ne 0){throw 'Kotlin wire compile failed'}
& javac -encoding UTF-8 -cp "$output;$TestClasspath" -d $output (Join-Path $testRoot 'SelfHostedWorkoutPayloadTest.java') (Join-Path $testRoot 'SelfHostedWorkoutSyncTest.java')
if($LASTEXITCODE -ne 0){throw 'Wire test compile failed'}
& java -cp "$output;$TestClasspath" org.junit.runner.JUnitCore nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedWorkoutPayloadTest nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedWorkoutSyncTest
if($LASTEXITCODE -ne 0){throw 'Wire contract tests failed'}
& java -cp "$output;$TestClasspath" nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedWorkoutPayloadTest | Set-Content -LiteralPath (Join-Path $output 'cycling-wire.json') -Encoding utf8
if($LASTEXITCODE -ne 0){throw 'Wire fixture emission failed'}
