#!/usr/bin/env bash
#
# SyncTank contract-check — step 5: the merge gate. The only step that can fail on purpose.
#
# FAIL_ON_BREAKING
#   pull-request (default) fail only on pull_request / pull_request_target events
#   always                 fail on every event
#   never                  report only
#
# Why "pull-request" is the default (Day 12 F2): contract.yml used to fail on push to main too.
# A failed push run skips "Commit baseline file", so once any BREAKING change reached main the
# baseline never advanced again, and every later PR was diffed against a stale contract and
# failed forever. A gate belongs in front of the merge, not after it.
set -euo pipefail

# Fail closed: a gate with no inputs must not pass. (Seen in the Day 12 dry run — a blank line
# in a pasted command dropped every variable and the gate answered "no changes".)
: "${STATUS:?gate.sh needs STATUS from diff.sh}"
if [ "$STATUS" != no-baseline ]; then
  : "${CHANGE_COUNT:?gate.sh needs CHANGE_COUNT from diff.sh}"
  : "${GATE_SEVERITY:?gate.sh needs GATE_SEVERITY from diff.sh}"
fi
COUNT="${CHANGE_COUNT:-0}"
GATE="${GATE_SEVERITY:-NONE}"
SEVERITY="${SEVERITY:-}"
SEEDED="${REGISTRY_SEEDED:-false}"
MODE="${FAIL_ON_BREAKING:-pull-request}"
APPROVED="${BREAKING_APPROVED:-false}"
LABEL="${APPROVAL_LABEL:-}"
EVENT="${GITHUB_EVENT_NAME:-local}"
KEY="${INPUT_SPEC_KEY:-api}"

if [ "$STATUS" = no-baseline ]; then
  echo "No baseline yet — gate not applicable."
  exit 0
fi
if [ "$COUNT" = 0 ]; then
  echo "No API contract changes in ${KEY}."
  exit 0
fi

case "$MODE" in
  always) ENFORCE=true ;;
  never)  ENFORCE=false ;;
  pull-request)
    case "$EVENT" in
      pull_request|pull_request_target) ENFORCE=true ;;
      *) ENFORCE=false ;;
    esac ;;
  *)
    echo "::error title=Contract check::fail-on-breaking must be pull-request, always or never (got '$MODE')"
    exit 1 ;;
esac

BASIS="effective severity after the Impact Radar"
[ "$SEEDED" = true ] || BASIS="classifier severity — no Impact Radar evidence for this run"

case "$GATE" in
  BREAKING)
    if [ "$APPROVED" = true ]; then
      echo "::warning title=Breaking change approved (${KEY})::BREAKING (${BASIS}), merge allowed by the '${LABEL}' label. The approval is recorded on this pull request."
    elif [ "$ENFORCE" != true ]; then
      echo "::warning title=Breaking change (${KEY})::BREAKING (${BASIS}). Not enforced on '${EVENT}' events (fail-on-breaking: ${MODE})."
    else
      MSG="BREAKING (${BASIS}; classifier said ${SEVERITY}). See the PR comment and the contract-check artifact."
      [ -n "$LABEL" ] && MSG="$MSG If this change is intentional and every consumer is updated, add the '${LABEL}' label."
      echo "::error title=Breaking contract change (${KEY})::${MSG}"
      exit 1
    fi ;;
  DANGEROUS)
    echo "::warning title=Dangerous contract change (${KEY})::Type-safe but may change runtime behaviour (${BASIS}). Review the diff report before merging." ;;
  SAFE_WITH_NOTE)
    echo "::notice title=Downgraded contract change (${KEY})::Classified ${SEVERITY}, but no registered consumer compiles against it. Merge is unblocked; see the PR comment." ;;
  ADDITIVE)
    echo "Additive contract change in ${KEY} — safe for existing consumers." ;;
  *)
    echo "::error title=Contract check::unexpected gate severity '${GATE}'"
    exit 1 ;;
esac