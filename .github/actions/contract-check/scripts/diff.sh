#!/usr/bin/env bash
#
# SyncTank contract-check — step 2: seed the Impact Radar, diff, and decide what gates the merge.
#
# Outputs (all strings):
#   status              no-baseline | diffed
#   changed             true | false          (openapi-diff's raw "anything differs")
#   change-count        number of CLASSIFIED changes
#   severity            classifier verdict    ADDITIVE | DANGEROUS | BREAKING | NONE
#   effective-severity  after Impact Radar    SAFE_WITH_NOTE | ADDITIVE | DANGEROUS | BREAKING | NONE
#   registry-seeded     true only when the radar holds real usage rows for this consumer
#   gate-severity       what the gate acts on (see "Day 12 F3" below)
#   diff-report         path to diff-report.json
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

: "${PLATFORM_URL:?}"
: "${INPUT_CANDIDATE_SPEC:?candidate-spec is required}"
: "${INPUT_BASELINE_SPEC:?baseline-spec is required}"
: "${INPUT_SPEC_KEY:?spec-key is required}"

# spec-key becomes part of a directory name, an artifact name and an HTML comment marker.
if [[ ! "$INPUT_SPEC_KEY" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "::error title=Contract check::spec-key '$INPUT_SPEC_KEY' may contain only letters, digits, '.', '_' and '-'"
  exit 1
fi

OUT="$(work_dir "$INPUT_SPEC_KEY")"
mkdir -p "$OUT"

write_outputs() {   # status changed count severity effective seeded gate report
  set_output status             "$1"
  set_output changed            "$2"
  set_output change-count       "$3"
  set_output severity           "$4"
  set_output effective-severity "$5"
  set_output registry-seeded    "$6"
  set_output gate-severity      "$7"
  set_output diff-report        "$8"
}

# ---------- the candidate: must exist, must be JSON ----------
CANDIDATE="$(abs_path "$INPUT_CANDIDATE_SPEC")"
if [ ! -f "$CANDIDATE" ]; then
  echo "::error title=Contract check::candidate-spec not found at $INPUT_CANDIDATE_SPEC — did the spec-generation step run and succeed?"
  exit 1
fi
jq empty "$CANDIDATE" 2> /dev/null \
  || { echo "::error title=Contract check::candidate-spec $INPUT_CANDIDATE_SPEC is not valid JSON"; exit 1; }
cp "$CANDIDATE" "$OUT/candidate-openapi.json"

# ---------- no baseline yet: report and pass, exactly as contract.yml always has ----------
BASELINE="$(abs_path "$INPUT_BASELINE_SPEC")"
if [ ! -f "$BASELINE" ]; then
  echo "::notice title=Contract check (${INPUT_SPEC_KEY})::No baseline at $INPUT_BASELINE_SPEC yet — nothing to diff against. It is created by the first push to the default branch."
  write_outputs no-baseline false 0 NONE NONE false NONE ""
  summary "### Contract check — \`${INPUT_SPEC_KEY}\`" "" "No baseline yet — nothing to diff against."
  exit 0
fi
jq empty "$BASELINE" 2> /dev/null \
  || { echo "::error title=Contract check::baseline-spec $INPUT_BASELINE_SPEC is not valid JSON"; exit 1; }

# ---------- Impact Radar: register the consumer from the BASELINE (Day 06 §2.2) ----------
REGISTRY_SEEDED=false
if [ -n "${INPUT_FRONTEND_SRC:-}" ]; then
  : "${INPUT_CONSUMER_APP_NAME:?consumer-app-name is required when frontend-src is set}"
  FRONTEND="$(abs_path "$INPUT_FRONTEND_SRC")"
  [ -d "$FRONTEND" ] \
    || { echo "::error title=Contract check::frontend-src $INPUT_FRONTEND_SRC is not a directory"; exit 1; }

  group "Impact Radar — register ${INPUT_CONSUMER_APP_NAME}"
  jq -n \
    --rawfile spec "$BASELINE" \
    --arg app "$INPUT_CONSUMER_APP_NAME" \
    --arg team "${INPUT_CONSUMER_TEAM:-}" \
    --arg repo "${INPUT_CONSUMER_REPO:-${GITHUB_REPOSITORY:-local}}" \
    --arg version "${GITHUB_SHA:-local}" \
    --arg src "$FRONTEND" \
    '{appName:$app, team:$team, repo:$repo, clientVersion:$version,
      frontendSrcPath:$src, baselineSpec:$spec}' > "$OUT/seed-request.json"

  curl -fsS -X POST "$PLATFORM_URL/registry/apps" \
    -H "Content-Type: application/json" \
    --data-binary @"$OUT/seed-request.json" \
    -o "$OUT/seed-result.json"
  jq . "$OUT/seed-result.json"

  USAGES="$(jq '(.endpointUsages // 0) + (.fieldUsages // 0)' "$OUT/seed-result.json")"
  if [ "$USAGES" -gt 0 ]; then
    REGISTRY_SEEDED=true
  else
    # Day 12 F3. The platform answers 200 for an EMPTY seed ("no file imports the generated
    # client"). The radar would then read "no consumers" and downgrade every BREAKING change —
    # the Day 10 F1 failure through a different door. Evidence of absence requires evidence.
    echo "::warning title=Impact Radar has no evidence (${INPUT_SPEC_KEY})::Seeding ${INPUT_CONSUMER_APP_NAME} from $INPUT_FRONTEND_SRC found zero usages (see notes above). The gate will use the classifier's own severity, not the radar's."
  fi

  if [ -n "${INPUT_DEMO_TELEMETRY:-}" ]; then
    printf '%s' "$INPUT_DEMO_TELEMETRY" | jq -e 'type == "object" and all(.[]; type == "number")' > /dev/null \
      || { echo "::error title=Contract check::demo-telemetry must be a JSON object of endpoint -> calls/day"; exit 1; }
    # SEEDED, NOT MEASURED. Phase 5's interceptor is not built; the PR comment says so.
    printf '%s' "$INPUT_DEMO_TELEMETRY" | curl -fsS -X PUT \
      "$PLATFORM_URL/registry/apps/${INPUT_CONSUMER_APP_NAME}/traffic" \
      -H "Content-Type: application/json" --data-binary @-
    echo
  fi
  endgroup
else
  echo "::notice title=Contract check (${INPUT_SPEC_KEY})::No frontend-src given — the Impact Radar has no consumer evidence, so the gate uses the classifier's own severity."
fi

# ---------- the diff ----------
group "Contract diff — baseline vs candidate"
jq -n \
  --rawfile baseline "$BASELINE" \
  --rawfile candidate "$CANDIDATE" \
  '{baseline: $baseline, candidate: $candidate}' > "$OUT/diff-request.json"

curl -fsS -X POST "$PLATFORM_URL/diff" \
  -H "Content-Type: application/json" \
  --data-binary @"$OUT/diff-request.json" \
  -o "$OUT/diff-report.json"

jq -e 'has("highestSeverity") and (.changes | type == "array")' "$OUT/diff-report.json" > /dev/null \
  || { echo "::error title=Contract check::/diff did not return a diff report"; cat "$OUT/diff-report.json"; exit 1; }

jq '{changed, highestSeverity, effectiveSeverity,
     changes: [.changes[] | {severity, category, location}]}' "$OUT/diff-report.json"
endgroup

CHANGED="$(jq -r '.changed' "$OUT/diff-report.json")"
COUNT="$(jq '.changes | length' "$OUT/diff-report.json")"
# `// empty` — the Day 05 trap: jq -r prints a JSON null as the truthy string "null".
SEVERITY="$(jq -r '.highestSeverity // empty' "$OUT/diff-report.json")"
EFFECTIVE="$(jq -r '.effectiveSeverity // empty' "$OUT/diff-report.json")"

if [ "$COUNT" -eq 0 ]; then
  # Day 12 F1. DiffService's max() over zero changes defaults to ADDITIVE, and contract.yml's
  # report step was gated on "== ADDITIVE" — so every PR that touched no API paid for an AI call
  # and got a "No API changes were detected" comment (PR #21). An empty diff is NONE.
  SEVERITY=NONE
  EFFECTIVE=NONE
  GATE=NONE
elif [ "$REGISTRY_SEEDED" = true ]; then
  GATE="$EFFECTIVE"
else
  GATE="$SEVERITY"   # Day 12 F3: no radar evidence, no radar downgrade
fi

write_outputs diffed "$CHANGED" "$COUNT" "$SEVERITY" "$EFFECTIVE" "$REGISTRY_SEEDED" "$GATE" "$OUT/diff-report.json"

summary "### Contract check — \`${INPUT_SPEC_KEY}\`" "" \
  "| Classified changes | Classifier | After Impact Radar | Radar evidence | Gate acts on |" \
  "|---|---|---|---|---|" \
  "| ${COUNT} | ${SEVERITY} | ${EFFECTIVE} | ${REGISTRY_SEEDED} | **${GATE}** |"