#!/usr/bin/env bash
# Day 13 — every "is it really ready?" question for the demo, answered in one run.
#
#   bash demo/preflight.sh cloud    (in a terminal where infra/aws/deploy/00-env.sh is sourced)
#   bash demo/preflight.sh local
#
# Read-only: changes nothing anywhere. Exit 0 only when every check passes.
# Run it before every rehearsal, every recording, and once more before walking on stage.
# shellcheck source=demo/lib.sh disable=SC2015
source "$(dirname "$0")/lib.sh"
set +e   # a failed check is counted and reported, not fatal
cd "$ROOT" || exit 1

MODE="${1:-}"
case "$MODE" in cloud|local) ;; *) die "usage: bash demo/preflight.sh cloud|local" ;; esac

PASS=0; FAIL=0
pass() { ok "$*"; PASS=$((PASS + 1)); }
fail() { bad "$*"; FAIL=$((FAIL + 1)); }

say "1. tools"
TOOLS="git gh jq curl java mvn npm lsof"
[ "$MODE" = "cloud" ] && TOOLS="$TOOLS aws"
[ "$MODE" = "local" ] && TOOLS="$TOOLS docker"
for t in $TOOLS; do
  command -v "$t" >/dev/null 2>&1 && pass "$t" || fail "$t not found"
done
gh auth status >/dev/null 2>&1 && pass "gh is logged in" || fail "gh is not logged in (gh auth login)"

say "2. git"
git fetch -q origin main 2>/dev/null
BRANCH="$(current_branch)"
[ "$BRANCH" = "main" ] && pass "on main" || warn "on $BRANCH (walk on stage from main; Slide 3 switches branch itself)"
[ "$(git rev-parse main)" = "$(git rev-parse origin/main)" ] \
  && pass "local main = origin/main ($(git rev-parse --short main))" \
  || fail "local main differs from origin/main — git switch main && git pull --ff-only"

say "3. dashboard target (orders-frontend/public/config.js)"
P="$(platform_url)"
if [ "$MODE" = "cloud" ]; then
  case "$P" in http://synctank-alb-*) pass "platformBase = $P" ;; *) fail "platformBase = $P — run: bash demo/target.sh cloud" ;; esac
else
  [ "$P" = "http://localhost:8081" ] && pass "platformBase = $P" || fail "platformBase = $P — run: bash demo/target.sh local"
fi
[ "$(orders_url)" = "http://localhost:$DISPLAY_PORT" ] && pass "ordersBase = $(orders_url)" \
  || fail "ordersBase = $(orders_url) — run: bash demo/target.sh $MODE"

say "4. platform at $P"
[ "$(http_code "$P/health")" = "200" ] && pass "/health 200" || fail "/health not reachable (cloud: IP allowlist? run infra/aws/deploy/15-allow-my-ip.sh)"
READY="$(curl -s --max-time 10 "$P/health/ready" | jq -r '.ready' 2>/dev/null)"
[ "$READY" = "true" ] && pass "/health/ready: database and spec store reachable" || fail "/health/ready is not ready ($READY)"
AGENT="$(curl -s --max-time 10 "$P/agent/status")"
[ "$(echo "$AGENT" | jq -r '.enabled' 2>/dev/null)" = "true" ] && pass "agent enabled" || fail "agent disabled or unreachable"
[ "$(echo "$AGENT" | jq -r '.canOpenPullRequests' 2>/dev/null)" = "true" ] && pass "agent can open pull requests (GitHub token loaded)" \
  || fail "agent is draft-only — no GitHub token in the platform's secret"
ACAO="$(curl -s -o /dev/null -D - --max-time 10 -H "Origin: $DASHBOARD_ORIGIN" "$P/agent/status" | tr -d '\r' \
  | awk -F': ' 'tolower($1)=="access-control-allow-origin"{print $2}')"
[ "$ACAO" = "$DASHBOARD_ORIGIN" ] && pass "CORS allows $DASHBOARD_ORIGIN (open the dashboard at exactly that address)" \
  || fail "CORS does not allow $DASHBOARD_ORIGIN (got '$ACAO') — AGENT_DASHBOARD_ORIGINS"
SCREENS="$(curl -s --max-time 10 "$P/registry/apps" | jq -r '.[] | select(.appName=="customer-portal") | .screens | join(",")' 2>/dev/null)"
case ",$SCREENS," in
  *,order-detail,*) pass "customer-portal registered, screens: $SCREENS" ;;
  *) fail "customer-portal not registered with screen order-detail ('$SCREENS') — seed: 40-seed-demo-data.sh" ;;
esac
CALLS="$(curl -s --max-time 10 "$P/registry/apps" | jq -r '[.[].totalCallsPerDay] | add // 0' 2>/dev/null)"
[ "${CALLS:-0}" -gt 0 ] 2>/dev/null && pass "seeded telemetry: $CALLS calls/day" || fail "no seeded telemetry"
HIST="$(curl -s --max-time 10 "$P/specs/$SPEC_KEY/history" | jq -r '"\(length) \([.[] | select(.baseline)] | length)"' 2>/dev/null)"
[ "${HIST% *}" -ge 1 ] 2>/dev/null && [ "${HIST#* }" = "1" ] && pass "spec history: ${HIST% *} version(s), baseline set" \
  || fail "spec history/baseline missing ('$HIST')"
REMAIN="$(curl -s --max-time 10 "$P/health/ai" | jq -r '.remainingToday' 2>/dev/null)"
[ "${REMAIN:-0}" -ge 20 ] 2>/dev/null && pass "AI calls remaining today: $REMAIN" || fail "AI budget nearly spent ($REMAIN left)"

say "5. this laptop"
[ "$(http_code "http://localhost:$DISPLAY_PORT/api/orders/1")" = "200" ] && pass "orders-backend on :$DISPLAY_PORT" \
  || fail "orders-backend not on :$DISPLAY_PORT — bash demo/backend.sh start"
[ -z "$(port_pid 8080)" ] && pass "port 8080 free (Slide 3's mvn verify needs it)" \
  || fail "port 8080 in use by pid $(port_pid 8080) — Slide 3 would generate the wrong spec"
[ "$(http_code "$DASHBOARD_ORIGIN")" = "200" ] && pass "dashboard on $DASHBOARD_ORIGIN" \
  || fail "dashboard not on $DASHBOARD_ORIGIN — cd orders-frontend && npm start"

say "6. GitHub"
AGENT_BRANCHES="$(git ls-remote --heads origin 'agent/*' | wc -l | tr -d ' ')"
[ "$AGENT_BRANCHES" = "0" ] && pass "no agent/* branches (Approve cannot collide)" \
  || fail "$AGENT_BRANCHES agent/* branch(es) on GitHub — bash demo/reset.sh"
STAGE_PR="$(gh pr list --repo "$REPO_SLUG" --head "$STAGE_BRANCH" --state open --json number --jq '.[0].number' 2>/dev/null)"
if [ -n "$STAGE_PR" ]; then
  pass "stage PR #$STAGE_PR is open"
  REPORT="$(gh pr view "$STAGE_PR" --repo "$REPO_SLUG" --json comments \
    --jq "[.comments[].body | select(contains(\"synctank-contract-check:$SPEC_KEY\"))] | last // \"\"" 2>/dev/null)"
  case "$REPORT" in
    *BREAKING*customer-portal*) pass "stage PR carries the contract report (BREAKING, customer-portal)" ;;
    *) fail "stage PR has no contract report naming customer-portal yet" ;;
  esac
else
  fail "no open PR from $STAGE_BRANCH — guide Part C"
fi

if [ "$MODE" = "cloud" ]; then
  say "7. AWS"
  RUNNING="$(AWS_PAGER="" aws ecs describe-services --region "${AWS_REGION:-}" --cluster "${CLUSTER:-}" \
    --services "${SERVICE:-}" --query 'services[0].runningCount' --output text 2>/dev/null)"
  [ "$RUNNING" = "1" ] && pass "ECS service running 1 task" || fail "ECS runningCount='$RUNNING' (00-env.sh sourced? bash demo/cloud-up.sh)"
fi

say "RESULT: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && echo "${C_OK}READY${C_OFF}" || { echo "${C_BAD}NOT READY — fix every ✘ above${C_OFF}"; exit 1; }
