#!/usr/bin/env bash
# Publica un resumen verificable (resultado real de Gradle/JUnit + líneas clave) como Issue, porque los logs
# completos de Actions no se pueden descargar sin sesión. No inventa resultados: solo copia lo que hay en diag/.
set -u
STATUS="sin-ejecutar"; [ -f diag/status.txt ] && STATUS="$(cat diag/status.txt)"
TESTS="?"; FAILS="?"; ERRS="?"
XML="$(find diag/results -name 'TEST-*.xml' 2>/dev/null | head -1)"
if [ -n "$XML" ]; then
  TESTS="$(grep -o 'tests="[0-9]*"' "$XML" | head -1 | tr -dc 0-9)"
  FAILS="$(grep -o 'failures="[0-9]*"' "$XML" | head -1 | tr -dc 0-9)"
  ERRS="$(grep -o 'errors="[0-9]*"' "$XML" | head -1 | tr -dc 0-9)"
fi
if [ "$STATUS" = "0" ] && [ "$TESTS" != "?" ] && [ "${TESTS:-0}" -ge 1 ] && [ "${FAILS:-1}" = "0" ] && [ "${ERRS:-1}" = "0" ]; then VEREDICTO="PASS"; else VEREDICTO="FAIL"; fi
{
  echo "RESULT: $VEREDICTO (gradle exit=$STATUS, tests=$TESTS, failures=$FAILS, errors=$ERRS)"
  echo "run: $GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID  commit: $GITHUB_SHA"
  echo; echo "### Casos ejecutados (del XML de JUnit)"
  [ -n "$XML" ] && grep -o '<testcase [^>]*' "$XML" | cut -c1-260
  echo; echo "### Runner"; cat diag/runner.txt 2>/dev/null
  echo; echo "### Dispositivo"; cat diag/device.txt 2>/dev/null
  echo; echo "### Gradle (últimas 70 líneas)"; echo '```'; tail -n 70 diag/gradle.txt 2>/dev/null | cut -c1-300; echo '```'
  echo; echo "### Líneas clave de logcat"; echo '```'
  grep -E "FATAL|AndroidRuntime|MigrationTestHelper|IllegalStateException|Migration|room|SQLiteException|OutOfMemory|lowmemorykiller|am_kill|Process .* died" diag/logcat.txt 2>/dev/null | tail -n 40 | cut -c1-260
  echo '```'
} > diag/resumen.md
head -c 60000 diag/resumen.md > diag/resumen-corto.md
gh issue create --title "CI Room 37→38 (emulador): $VEREDICTO — run $GITHUB_RUN_NUMBER" --body-file diag/resumen-corto.md || true
cat diag/resumen-corto.md >> "$GITHUB_STEP_SUMMARY" || true
