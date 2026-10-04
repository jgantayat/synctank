#!/usr/bin/env bash
# Day 13 — after EVERY rehearsal or recording: put the stage back exactly as it started.
#
#   bash demo/reset.sh          asks before closing agent PRs
#   bash demo/reset.sh --yes    does not ask (only agent/* branches are ever touched)
#
# 1. order-detail.ts back to the committed version
# 2. back on main; spec + TypeScript client regenerated from main's contract
# 3. display backend restarted from main
# 4. every open agent/* PR closed and every agent/* branch deleted
#
# Why 4 matters: the agent names its branch agent/add-<schema>-<field>-<requestId>. The cloud
# task's database is ephemeral, so request ids restart at 1 after every scale-up. If
# agent/add-order-response-customer-email-1 is still on GitHub, the next Approve collides with
# it and fails with a 500 — on stage. The stage prop PR (stage/compile-break) is NOT touched.
# shellcheck source=demo/lib.sh disable=SC2015
source "$(dirname "$0")/lib.sh"
need git gh mvn npm lsof
cd "$ROOT" || exit 1
ASSUME_YES=false
[ "${1:-}" = "--yes" ] && ASSUME_YES=true

say "1/4 working tree"
git checkout -- orders-frontend/src/app/order-detail/order-detail.ts
DIRTY="$(git status --porcelain | grep -v ' orders-frontend/public/config.js$' || true)"
[ -z "$DIRTY" ] || die "uncommitted changes other than config.js — commit or stash them first:
$DIRTY"
ok "order-detail.ts restored; config.js left as demo/target.sh set it"

say "2/4 back to main, contract regenerated from main"
git switch -q main
HOLDER="$(port_pid 8080)"
[ -z "$HOLDER" ] || die "port 8080 is in use by pid $HOLDER — mvn verify needs it. Stop it and re-run."
(cd orders-backend && mvn -q -DskipTests verify)
(cd orders-frontend && npm run -s generate:api >/dev/null)
if grep -q '"Money"' orders-backend/target/openapi.json; then die "spec still has Money — not main's contract"; fi
ok "on main; spec and generated client are main's (amount: number)"

say "3/4 display backend from main"
bash demo/backend.sh restart

say "4/4 agent pull requests and branches"
PRS="$(gh pr list --repo "$REPO_SLUG" --state open --limit 100 --json number,headRefName \
  --jq '.[] | select(.headRefName | startswith("agent/")) | "\(.number) \(.headRefName)"')"
if [ -n "$PRS" ]; then
  echo "open agent PRs (number branch):"; echo "$PRS"
  if [ "$ASSUME_YES" = "false" ]; then
    read -r -p "Close them and delete their branches? [y/N] " answer
    [ "$answer" = "y" ] || die "left them open. The next Approve may collide — see the header of this script."
  fi
  echo "$PRS" | while read -r number _; do
    gh pr close "$number" --repo "$REPO_SLUG" --delete-branch \
      --comment "Closed by demo/reset.sh — Day 13 rehearsal PR, never meant to merge."
  done
fi
# Branches without a PR: an Approve that failed half-way leaves one behind.
git ls-remote --heads origin 'agent/*' | awk '{print $2}' | sed 's#^refs/heads/##' | while read -r branch; do
  [ -n "$branch" ] || continue
  echo "deleting leftover branch $branch"
  git push -q origin --delete "$branch"
done
LEFT="$(git ls-remote --heads origin 'agent/*' | wc -l | tr -d ' ')"
[ "$LEFT" = "0" ] && ok "no agent/* branches on GitHub" || die "$LEFT agent/* branch(es) still on GitHub"

say "reset complete — branch $(current_branch), dashboard -> $(platform_url)"
