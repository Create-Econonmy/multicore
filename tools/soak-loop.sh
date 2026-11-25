#!/usr/bin/env bash
# Soak test: runs the full-pack GameTest server again and again (every test plus SOAK_ROUNDS soak rounds each time:
# a Create train, a logistics request, machines in many regions and the chaos test at once) until HOURS have passed.
# Local test folder only (run/gametest-next). For each run it keeps the log and writes one summary line plus the
# MultiCore problems found in that log to $OUT/summary.txt. A run stuck for longer than RUN_MINUTES is killed and
# counted as a hang (its log is kept with the thread dumps MultiCore wrote).
#
#   bash tools/soak-loop.sh [HOURS=5] [SOAK_ROUNDS=15] [OUT=build/soak]
set -u
HOURS=${1:-5}
ROUNDS=${2:-15}
OUT=${3:-build/soak}
RUN_MINUTES=90
cd "$(dirname "$0")/.."
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
mkdir -p "$OUT"
END=$(( $(date +%s) + HOURS * 3600 ))
PATTERN='waited 10 s for the mod lock|waited 100 ms for the mod lock|stopped for 10 s|could not pause|Threading error|switching to vanilla|switched off for the rest|ConcurrentModification|ArrayIndexOutOf|Ticking block entity|Ticking entity|IllegalMonitorState|BootstrapMethodError|NoSuchMethodError|VerifyError|Error while waiting for train|failed at|waiting for work queued|Mod loading issue|split of .* taking over|Parallel tick is taking over|Exception in server tick|items lost or made up'
i=0
echo "soak started $(date) for $HOURS h, $ROUNDS soak rounds per run" >> "$OUT/summary.txt"
while [ "$(date +%s)" -lt "$END" ]; do
  i=$((i + 1))
  rm -rf run/gametest-next/world
  start=$(date +%s)
  timeout $((RUN_MINUTES * 60)) ./gradlew runGameTestServer -PgametestDir=run/gametest-next -PsoakRounds="$ROUNDS" --console=plain > "$OUT/run$i.out" 2>&1
  code=$?
  took=$(( ($(date +%s) - start) / 60 ))
  cp run/gametest-next/logs/latest.log "$OUT/run$i.log" 2>/dev/null
  if [ $code -eq 124 ]; then
    result="HUNG (killed after $RUN_MINUTES min)"
    powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'mutile core server' -and \$_.CommandLine -notmatch 'GradleDaemon' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1
  else
    result=$(grep -a -E 'All [0-9]+ required tests passed|[0-9]+ required tests failed' "$OUT/run$i.out" | head -1 | sed 's/^.*GameTestServer\]: //')
    [ -z "$result" ] && result="NO RESULT (exit $code; see run$i.out)"
  fi
  echo "== run $i ($(date +%H:%M), $took min): $result" >> "$OUT/summary.txt"
  grep -a -E "$PATTERN" "$OUT/run$i.log" 2>/dev/null | sed 's/^\[[^]]*\] //' | cut -c1-220 | sort | uniq -c | sort -rn | head -15 >> "$OUT/summary.txt"
done
echo "soak finished $(date) after $i runs" >> "$OUT/summary.txt"
