#!/usr/bin/env bash
# Day 13 — Slide 3, beat 4: apply the reviewed migration, build green, screen works.
#
# 1. copies demo/slide3/order-detail.migrated.ts over order-detail.ts (shows the diff)
# 2. rebuilds the frontend — it now compiles against the new contract
# 3. restarts the display backend on :8082 from THIS branch, so it serves `total`
# Then refresh the browser: Order Detail shows "INR 249.50".
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need npm git
cd "$ROOT" || exit 1

TARGET=orders-frontend/src/app/order-detail/order-detail.ts
grep -q 'Money total' orders-backend/src/main/java/com/synctank/orders/api/OrderResponse.java \
  || die "not on the prop branch ($(current_branch)). Run: git switch $STAGE_BRANCH"
grep -q 'total' orders-frontend/src/app/generated/model/order-response.ts \
  || die "the generated client is not the Money version — run bash demo/slide3-break.sh first"

T0=$(now)
say "1/3 the migration — reviewed against the AI's suggestion on the pull request"
# $(...) drops trailing newlines: the committed order-detail.ts has none at EOF, so the diff the
# audience reads is the getter and nothing else, however the .migrated.ts file was saved.
printf '%s' "$(cat demo/slide3/order-detail.migrated.ts)" > "$TARGET"
git --no-pager diff -- "$TARGET"

say "2/3 build against the new contract"
(cd orders-frontend && NO_COLOR=1 npm run -s build >"$STATE_DIR/slide3-migrate-build.log" 2>&1) \
  || { tail -25 "$STATE_DIR/slide3-migrate-build.log"; die "build still failing"; }
ok "frontend compiles against the new contract"

say "3/3 restart orders-backend on :$DISPLAY_PORT from $(current_branch)"
bash demo/backend.sh restart

say "done in $(( $(now) - T0 ))s — refresh $DASHBOARD_ORIGIN: Order Detail shows the amount again"
