#!/usr/bin/env bash
#
# SyncTank contract-check — step 3: the plain-English change report (Day 05 / Day 06).
#
# Runs only when there is at least one classified change (action.yml's `if:`).
# NEVER fails the job: the report is advisory. A failure here is a warning, and comment.js
# falls back to the deterministic diff report — the same "narration may fail, the verdict may
# not" rule ChangeReportService has followed since Day 05.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

: "${PLATFORM_URL:?}" "${INPUT_SPEC_KEY:?}" "${DIFF_REPORT:?}"
OUT="$(work_dir "$INPUT_SPEC_KEY")"

if [ -n "${INPUT_FRONTEND_SRC:-}" ]; then
  FRONTEND="$(abs_path "$INPUT_FRONTEND_SRC")"
else
  # /report requires an existing directory (Day 12 F4). With no consumer source to scan, an
  # empty directory is the honest input: zero usage hits, rather than a made-up path whose
  # ripgrep error message would be reported as a "usage".
  FRONTEND="$(mktemp -d)"
fi

jq -n \
  --slurpfile diff "$DIFF_REPORT" \
  --arg src "$FRONTEND" \
  '{diff: $diff[0], frontendSrcPath: $src}' > "$OUT/report-request.json"

group "Change report"
HTTP="$(curl -s --max-time 90 -o "$OUT/report.json" -w '%{http_code}' \
  -X POST "$PLATFORM_URL/report" \
  -H "Content-Type: application/json" \
  --data-binary @"$OUT/report-request.json")" || HTTP=000

if [ "$HTTP" = 200 ] && jq -e '.changes | type == "array"' "$OUT/report.json" > /dev/null 2>&1; then
  jq '{summary, changes: (.changes | length), openQuestions}' "$OUT/report.json"
  set_output report "$OUT/report.json"
else
  echo "::warning title=Change report unavailable (${INPUT_SPEC_KEY})::/report answered HTTP $HTTP. The PR comment will show the deterministic classification instead."
  cat "$OUT/report.json" 2> /dev/null || true
  echo
  set_output report ""
fi
endgroup