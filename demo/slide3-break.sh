#!/usr/bin/env bash
# Day 13 — Slide 3, beat 1: Level 1 (Sync). The contract change becomes a compile error.
#
#   bash demo/slide3-break.sh                  full: Java source -> spec -> TS client -> build
#   bash demo/slide3-break.sh --skip-backend   reuse the spec already generated backstage
#
# Runs on the prop branch (stage/compile-break), where OrderResponse.amount became Money total.
# Exit 0 means the build FAILED the way the demo needs it to. Exit 1 means it did not.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need mvn npm git lsof
cd "$ROOT" || exit 1

SKIP_BACKEND=false
[ "${1:-}" = "--skip-backend" ] && SKIP_BACKEND=true

RECORD=orders-backend/src/main/java/com/synctank/orders/api/OrderResponse.java
SPEC=orders-backend/target/openapi.json
MODEL=orders-frontend/src/app/generated/model/order-response.ts
BUILD_LOG="$STATE_DIR/slide3-build.log"

grep -q 'Money total' "$RECORD" \
  || die "OrderResponse has no 'Money total' — you are on $(current_branch). Run: git switch $STAGE_BRANCH"

T0=$(now)

say "The change: an ordinary backend refactor — reviewed, merged, nothing complains"
git --no-pager diff --stat main -- orders-backend/src/main
git --no-pager diff main -- "$RECORD"

if [ "$SKIP_BACKEND" = "false" ]; then
  HOLDER="$(port_pid 8080)"
  [ -z "$HOLDER" ] || die "port 8080 is in use by pid $HOLDER. mvn verify needs it to write the spec — with it taken, the spec would come from that other app and nothing would break. Stop it, or use --skip-backend."
  say "1/3 regenerate the OpenAPI spec from the Java source"
  (cd orders-backend && mvn -q -DskipTests verify)
else
  say "1/3 spec regenerated backstage (--skip-backend)"
fi
grep -q '"Money"' "$SPEC" || die "$SPEC has no Money schema — it was not generated from this branch. Run without --skip-backend."
echo "spec: $SPEC  (Money schema present, amount gone)"

say "2/3 regenerate the TypeScript client from that spec"
(cd orders-frontend && npm run -s generate:api >/dev/null)
grep -q 'total' "$MODEL" || die "$MODEL was not regenerated with 'total'"
echo "client: $MODEL now has 'total', not 'amount'"

say "3/3 build the frontend against the new contract"
set +e
(cd orders-frontend && NO_COLOR=1 FORCE_COLOR=0 npm run -s build >"$BUILD_LOG" 2>&1)
RC=$?
set -e

if [ $RC -eq 0 ]; then
  bad "the build PASSED — no compile break. Is order-detail.ts already migrated? Run: bash demo/reset.sh"
  exit 1
fi

grep -A6 '\[ERROR\]' "$BUILD_LOG" || tail -25 "$BUILD_LOG"
ERRORS=$(grep -c '\[ERROR\]' "$BUILD_LOG" || true)
say "BUILD FAILED — ${ERRORS} compile error(s), $(( $(now) - T0 ))s after the backend change. Exact file, exact line."
