#!/bin/bash
# Prints the run's one test-count line from the selftest logs in $1 (default: .):
#   PASS and FAIL lines, and the sum of the suites' "N passed, M failed" summaries.
# "System chat:" lines are the server echoing a line to players, so they are skipped (they would count it twice).
# A line is a check by the first " PASS " or " FAIL " in it; any other SELFTEST line holding "N passed, M failed"
# is a suite summary, whatever text follows "failed" (map's "... 0 failed (a later check follows)").
d=${1:-.}
logs=()
for f in server.log server2.log client.log; do [ -f "$d/$f" ] && logs+=("$d/$f"); done
if [ ${#logs[@]} -eq 0 ]; then echo "TOTALS: no selftest logs"; exit 0; fi
grep -h 'SELFTEST ' "${logs[@]}" | grep -v 'System chat' | awk '
  match($0, / (PASS|FAIL) /) { if (substr($0, RSTART + 1, 4) == "PASS") p++; else f++; next }
  match($0, /[0-9]+ passed, [0-9]+ failed/) { split(substr($0, RSTART, RLENGTH), a, /[ ,]+/); n++; sp += a[1]; sf += a[3] }
  END { printf "TOTALS: PASS %d, FAIL %d | suites %d: %d passed, %d failed\n", p, f, n, sp, sf }'
