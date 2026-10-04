#!/usr/bin/env bash
# Day 13 (Round 2) — a second team adopts SyncTank, live, in its own repository.
#
#   bash demo/adopter.sh setup      ONE TIME: create jgantayat/synctank-adopter-live in its
#                                   "before" state (an API contract, no SyncTank anywhere)
#   bash demo/adopter.sh check      read-only: is the adopter repo in its "before" state?
#   bash demo/adopter.sh adopt      STAGE: add the one workflow file — that is the whole adoption
#   bash demo/adopter.sh prs        STAGE: open two PRs at once — one breaking, one additive
#   bash demo/adopter.sh status     STAGE: checks and contract verdicts of both PRs
#   bash demo/adopter.sh approve    OPTIONAL: label the breaking PR as an approved break
#   bash demo/adopter.sh reset [--yes]   AFTER EVERY RUN: back to the "before" state
#
# The adopter repo is a SANDBOX. reset force-pushes its main back to the `pre-adoption` tag.
# Nothing here ever touches jgantayat/synctank.
#
# Local clone: $ADOPTER_DIR, default ../synctank-adopter-live next to this repo.
# shellcheck source=demo/lib.sh disable=SC2015
source "$(dirname "$0")/lib.sh"
need git gh jq

ADOPTER_REPO="jgantayat/synctank-adopter-live"
ADOPTER_DIR="${ADOPTER_DIR:-$(cd "$ROOT/.." && pwd)/synctank-adopter-live}"
PRE_TAG="pre-adoption"
WORKFLOW=".github/workflows/contract.yml"
SPEC="payments-api/openapi.json"
BASELINE="payments-api/contract/openapi.baseline.json"
LABEL="contract:breaking-approved"
MARKER="synctank-contract-check:payments-api"
BREAK_BRANCH="drop-currency"
ADD_BRANCH="add-fee"

in_repo() { git -C "$ADOPTER_DIR" "$@"; }

pr_number() {  # $1 head branch -> open PR number or empty
  gh pr list --repo "$ADOPTER_REPO" --head "$1" --state open --json number --jq '.[0].number // empty'
}

write_contract() {  # the payments-api contract, pretty-printed so later jq edits make 1-line diffs
  jq -n '{
    openapi: "3.0.1",
    info: {title: "payments", version: "1"},
    paths: {"/api/payments/{id}": {get: {
      operationId: "getPayment",
      parameters: [{name: "id", in: "path", required: true, schema: {type: "integer"}}],
      responses: {"200": {description: "ok", content: {"application/json": {
        schema: {"$ref": "#/components/schemas/Payment"}}}}}}}},
    components: {schemas: {Payment: {type: "object", properties: {
      id: {type: "integer"}, amount: {type: "number"}, currency: {type: "string"}}}}}
  }' > "$1"
}

setup() {
  say "1/4 GitHub repository $ADOPTER_REPO"
  if gh repo view "$ADOPTER_REPO" >/dev/null 2>&1; then
    ok "exists"
  else
    gh repo create "$ADOPTER_REPO" --public \
      --description "SyncTank Round 2 demo: a second team adopts the contract check live. Sandbox — reset often."
    ok "created"
  fi

  say "2/4 local clone at $ADOPTER_DIR"
  if [ -d "$ADOPTER_DIR/.git" ]; then
    ok "already there — not touching it (use 'reset' to return to the before state)"
  else
    mkdir -p "$ADOPTER_DIR/payments-api/contract"
    write_contract "$ADOPTER_DIR/$SPEC"
    cp "$ADOPTER_DIR/$SPEC" "$ADOPTER_DIR/$BASELINE"
    cat > "$ADOPTER_DIR/README.md" <<'EOF'
# payments-api (SyncTank Round 2 demo)

A team's API contract — and, until the live demo, no SyncTank anywhere in this repository.

- `payments-api/openapi.json` — the contract this service produces (in a real service, generated at build time)
- `payments-api/contract/openapi.baseline.json` — the contract currently in production

Adopting SyncTank is one workflow file, added live on stage. This repository is reset after every rehearsal.
EOF
    in_repo init -q -b main
    in_repo add -A
    in_repo commit -q -m "payments-api: service contract and its production baseline"
    in_repo remote add origin "https://github.com/$ADOPTER_REPO.git"
    in_repo push -q -u origin main
    in_repo tag "$PRE_TAG"
    in_repo push -q origin "$PRE_TAG"
    ok "pushed main and tag $PRE_TAG ($(in_repo rev-parse --short HEAD))"
  fi

  say "3/4 approval label"
  gh label create "$LABEL" --repo "$ADOPTER_REPO" --color B60205 --force \
    --description "An intentional breaking contract change, reviewed and accepted" >/dev/null
  ok "$LABEL"

  say "4/4 optional: AI narration in this repo"
  if gh secret list --repo "$ADOPTER_REPO" 2>/dev/null | grep -q '^ANTHROPIC_API_KEY'; then
    ok "ANTHROPIC_API_KEY secret is set — reports will include the AI explanation"
  else
    warn "no ANTHROPIC_API_KEY secret — reports will be deterministic only (still complete)."
    warn "to add it (you type the key; it is never echoed):  gh secret set ANTHROPIC_API_KEY --repo $ADOPTER_REPO"
  fi
  echo
  echo "Next: bash demo/adopter.sh check"
}

check() {
  set +e
  local fails=0
  say "adopter repo — is it in its 'before' state?"
  gh repo view "$ADOPTER_REPO" >/dev/null 2>&1 && ok "$ADOPTER_REPO exists" || { bad "$ADOPTER_REPO missing — run setup"; fails=$((fails + 1)); }
  [ -d "$ADOPTER_DIR/.git" ] && ok "local clone $ADOPTER_DIR" || { bad "no local clone at $ADOPTER_DIR"; fails=$((fails + 1)); }
  if [ -d "$ADOPTER_DIR/.git" ]; then
    in_repo fetch -q origin --tags
    if [ "$(in_repo rev-parse origin/main)" = "$(in_repo rev-parse "$PRE_TAG^{commit}")" ]; then
      ok "remote main = $PRE_TAG (no workflow yet)"
    else
      bad "remote main is not at $PRE_TAG — run: bash demo/adopter.sh reset"; fails=$((fails + 1))
    fi
    [ -z "$(in_repo status --porcelain)" ] && ok "local clone clean" || { bad "local clone has changes"; fails=$((fails + 1)); }
  fi
  local open
  open="$(gh pr list --repo "$ADOPTER_REPO" --state open --json number --jq 'length' 2>/dev/null)"
  [ "$open" = "0" ] && ok "no open PRs" || { bad "$open open PR(s) — run: bash demo/adopter.sh reset"; fails=$((fails + 1)); }
  gh label list --repo "$ADOPTER_REPO" --json name --jq '.[].name' 2>/dev/null | grep -qx "$LABEL" \
    && ok "label $LABEL exists" || { bad "label $LABEL missing — run setup"; fails=$((fails + 1)); }
  if [ $fails -eq 0 ]; then echo "${C_OK}ADOPTER READY${C_OFF}"; else echo "${C_BAD}ADOPTER NOT READY ($fails)${C_OFF}"; exit 1; fi
}

adopt() {
  cd "$ADOPTER_DIR" || die "no clone at $ADOPTER_DIR — run setup"
  git switch -q main && git pull -q --ff-only
  [ ! -f "$WORKFLOW" ] || die "$WORKFLOW already exists — run: bash demo/adopter.sh reset"

  say "A team's repository: an API contract, and no SyncTank anywhere"
  git ls-files | sed 's/^/  /'

  mkdir -p .github/workflows
  cat > "$WORKFLOW" <<'EOF'
name: Contract check
on:
  pull_request:
    types: [opened, synchronize, reopened, labeled, unlabeled]
permissions:
  contents: read
  pull-requests: write
jobs:
  contract:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v5
      - uses: jgantayat/synctank/.github/actions/contract-check@v1
        with:
          candidate-spec: payments-api/openapi.json
          baseline-spec: payments-api/contract/openapi.baseline.json
          spec-key: payments-api
          anthropic-api-key: ${{ secrets.ANTHROPIC_API_KEY }}
EOF
  say "The whole adoption: one file, $(wc -l < "$WORKFLOW" | tr -d ' ') lines"
  cat "$WORKFLOW"
  git add "$WORKFLOW"
  git commit -q -m "Adopt the SyncTank contract check"
  git push -q origin main
  ok "pushed to main ($(git rev-parse --short HEAD)) — every pull request is now checked"
}

open_pr() {  # $1 branch, $2 jq edit, $3 commit message, $4 PR title, $5 PR body
  git switch -q main
  git switch -q -c "$1"
  jq "$2" "$SPEC" > "$SPEC.tmp" && mv "$SPEC.tmp" "$SPEC"
  git --no-pager diff --stat
  git --no-pager diff -U1 -- "$SPEC" | sed -n '5,$p'
  git commit -q -am "$3"
  git push -q -u origin "$1"
  gh pr create --repo "$ADOPTER_REPO" --base main --head "$1" --title "$4" --body "$5"
}

prs() {
  cd "$ADOPTER_DIR" || die "no clone at $ADOPTER_DIR — run setup"
  git switch -q main && git pull -q --ff-only
  [ -f "$WORKFLOW" ] || die "not adopted yet — run: bash demo/adopter.sh adopt"

  say "PR 1 — a developer drops a field someone may depend on"
  open_pr "$BREAK_BRANCH" 'del(.components.schemas.Payment.properties.currency)' \
    "Drop Payment.currency" "Drop Payment.currency" \
    "Currency is always INR now, so the field looks redundant."

  say "PR 2 — another developer adds a field"
  open_pr "$ADD_BRANCH" '.components.schemas.Payment.properties.fee = {"type": "number"}' \
    "Add Payment.fee" "Add Payment.fee" \
    "Expose the processing fee."

  git switch -q main
  say "Both checks are running now on GitHub's runners (about 3 minutes)"
  echo "Next: bash demo/adopter.sh status"
}

show_pr() {  # $1 branch, $2 label
  local n
  n="$(pr_number "$1")"
  if [ -z "$n" ]; then warn "$2: no open PR from $1"; return; fi
  say "$2 — PR #$n  https://github.com/$ADOPTER_REPO/pull/$n"
  gh pr checks "$n" --repo "$ADOPTER_REPO" 2>/dev/null | cut -f1,2 || true
  local body
  body="$(gh pr view "$n" --repo "$ADOPTER_REPO" --json comments \
    --jq "[.comments[].body | select(contains(\"$MARKER\"))] | last // \"\"")"
  if [ -z "$body" ]; then
    echo "  (no contract report yet — the check is still running)"
  else
    printf '%s\n' "$body" | grep -E '^- \*\*|Gate:|Blast radius|narration' | sed 's/^/  /'
  fi
}

status() {
  set +e
  show_pr "$BREAK_BRANCH" "Breaking change"
  show_pr "$ADD_BRANCH" "Additive change"
}

approve() {
  local n
  n="$(pr_number "$BREAK_BRANCH")"
  [ -n "$n" ] || die "no open PR from $BREAK_BRANCH"
  gh pr edit "$n" --repo "$ADOPTER_REPO" --add-label "$LABEL" >/dev/null
  ok "PR #$n labelled $LABEL — the check re-runs (~3 min) and records the break as approved"
}

reset_repo() {
  local assume_yes="${1:-}"
  cd "$ADOPTER_DIR" || die "no clone at $ADOPTER_DIR"
  say "adopter repo back to its 'before' state"
  if [ "$assume_yes" != "--yes" ]; then
    read -r -p "Close every open PR in $ADOPTER_REPO and force main back to $PRE_TAG? [y/N] " answer
    [ "$answer" = "y" ] || die "nothing changed"
  fi
  gh pr list --repo "$ADOPTER_REPO" --state open --json number --jq '.[].number' | while read -r n; do
    [ -n "$n" ] || continue
    gh pr close "$n" --repo "$ADOPTER_REPO" --delete-branch --comment "Closed by demo/adopter.sh reset — rehearsal PR." >/dev/null
    ok "closed PR #$n"
  done
  git fetch -q origin --prune --tags
  git ls-remote --heads origin | awk '{print $2}' | sed 's#^refs/heads/##' | while read -r b; do
    [ -n "$b" ] && [ "$b" != "main" ] || continue
    git push -q origin --delete "$b" && ok "deleted branch $b"
  done
  git switch -q main
  git reset -q --hard "$PRE_TAG"
  git push -q --force origin main
  for b in "$BREAK_BRANCH" "$ADD_BRANCH"; do git branch -q -D "$b" 2>/dev/null || true; done
  ok "main = $PRE_TAG ($(git rev-parse --short HEAD)) locally and on GitHub; no workflow"
}

case "${1:-}" in
  setup)   setup ;;
  check)   check ;;
  adopt)   adopt ;;
  prs)     prs ;;
  status)  status ;;
  approve) approve ;;
  reset)   reset_repo "${2:-}" ;;
  *) die "usage: bash demo/adopter.sh setup|check|adopt|prs|status|approve|reset [--yes]" ;;
esac
