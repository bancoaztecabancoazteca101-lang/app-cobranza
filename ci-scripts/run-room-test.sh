#!/usr/bin/env bash
# Ejecuta SOLO la prueba de migración Room 37->38 en el emulador y deja evidencia en diag/.
# Se ejecuta con bash (el shell por defecto de android-emulator-runner es sh y no acepta "pipefail").
set -uo pipefail
mkdir -p diag
{
  echo "== adb devices"; adb devices -l
  echo "== sdk / abi"; adb shell getprop ro.build.version.sdk; adb shell getprop ro.product.cpu.abi
  echo "== boot_completed"; adb shell getprop sys.boot_completed
  echo "== meminfo"; adb shell cat /proc/meminfo | head -3
} > diag/device.txt 2>&1
adb logcat -c || true
adb logcat -v time > diag/logcat.txt 2>&1 &
LOGCAT_PID=$!
set +e
gradle connectedDebugAndroidTest --stacktrace --info \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.matrizapp.PaseSyncMigrationRoomTest \
  > diag/gradle.txt 2>&1
STATUS=$?
kill "$LOGCAT_PID" 2>/dev/null
echo "$STATUS" > diag/status.txt
[ -d app/build/reports/androidTests ] && cp -r app/build/reports/androidTests diag/reports
[ -d app/build/outputs/androidTest-results ] && cp -r app/build/outputs/androidTest-results diag/results
exit "$STATUS"
