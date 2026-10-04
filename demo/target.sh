#!/usr/bin/env bash
# Day 13 — point the dashboard (and every demo script) at one platform.
#
#   bash demo/target.sh cloud     platformBase = $PLATFORM_URL (the ALB; source 00-env.sh first)
#   bash demo/target.sh local     platformBase = http://localhost:8081 (docker compose)
#   bash demo/target.sh show      print what config.js points at now
#   bash demo/target.sh restore   put the committed config.js back (do this before any commit)
#
# ordersBase goes to :8082 in both modes — see DISPLAY_PORT in lib.sh for why not :8080.
# config.js is a runtime asset: after switching, just refresh the browser. No rebuild.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"

write_config() {  # $1 platformBase, $2 label
  cat > "$CONFIG_JS" <<EOF
// Day 13 — WRITTEN BY demo/target.sh ($2). Not the committed file.
// Put the committed version back with:  bash demo/target.sh restore
window.syncTankConfig = {
  platformBase: '$1',
  ordersBase: 'http://localhost:$DISPLAY_PORT',
};
EOF
}

case "${1:-show}" in
  cloud)
    : "${PLATFORM_URL:?source infra/aws/deploy/00-env.sh in this terminal first}"
    case "$PLATFORM_URL" in
      http://synctank-alb-*) ;;
      *) die "PLATFORM_URL='$PLATFORM_URL' does not look like the SyncTank ALB" ;;
    esac
    write_config "$PLATFORM_URL" "cloud"
    ;;
  local)
    write_config "http://localhost:8081" "local"
    ;;
  restore)
    git -C "$ROOT" checkout -- orders-frontend/public/config.js
    ;;
  show) ;;
  *) die "usage: bash demo/target.sh cloud|local|show|restore" ;;
esac

echo "platformBase = $(platform_url)"
echo "ordersBase   = $(orders_url)"
if git -C "$ROOT" diff --quiet -- orders-frontend/public/config.js; then
  echo "config.js    = committed version"
else
  echo "config.js    = demo override (run 'bash demo/target.sh restore' before committing)"
fi
