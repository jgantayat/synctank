#!/usr/bin/env bash
# Day 13 — Slide 3, beat 2: Level 2 (Intelligence), live against the target platform.
#
# Sends the published baseline and the spec slide3-break.sh just generated to the platform the
# dashboard points at (the ALB in cloud mode), and prints what its classifier and Impact Radar
# say: every change, its severity, and who breaks — app, team, screens, calls/day.
#
# Read-only on the platform: POST /diff stores nothing.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need curl jq
cd "$ROOT" || exit 1

PLATFORM="$(platform_url)"
SPEC=orders-backend/target/openapi.json
[ -f "$SPEC" ] || die "no $SPEC — run bash demo/slide3-break.sh first"

BASELINE="$STATE_DIR/baseline.json"
curl -fsS --max-time 15 "$PLATFORM/specs/$SPEC_KEY/baseline" -o "$BASELINE" \
  || die "could not read the baseline from $PLATFORM — is it up, and is this IP allowed?"

say "Impact Radar — $PLATFORM"
jq -n --rawfile b "$BASELINE" --rawfile c "$SPEC" '{baseline: $b, candidate: $c}' \
| curl -fsS --max-time 30 -X POST "$PLATFORM/diff" -H 'Content-Type: application/json' --data-binary @- \
| jq -r '
    "Classified: \(.highestSeverity)    Effective after the Impact Radar: \(.effectiveSeverity)",
    "",
    (.changes[] | "  \(.severity | . + "          " | .[0:10]) \(.location)"),
    "",
    "Blast radius:",
    ( [.impact[] | select((.consumers | length) > 0)] as $hit
      | if ($hit | length) == 0 then "  no registered consumer is affected"
        else ($hit[] | "  \(.location)", (.consumers[] |
          "      \(.appName) (\(.team // "unknown team")) — screens: \(.screens | join(", ")), ~\(.callsPerDay) calls/day"))
        end )'
echo
echo "(calls/day are seeded demo telemetry, not live measurements)"
