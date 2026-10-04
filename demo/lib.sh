# shellcheck shell=bash disable=SC2034
# Day 13 — shared helpers for the demo kit. Sourced, never run.
#
# Written for the bash that ships with macOS (3.2): no associative arrays, no ${var,,},
# no mapfile, no GNU-only flags (sed -i, timeout, date -d).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG_JS="$ROOT/orders-frontend/public/config.js"
STATE_DIR="${TMPDIR:-/tmp}/synctank-demo"
mkdir -p "$STATE_DIR"

REPO_SLUG="jgantayat/synctank"
SPEC_KEY="orders-backend"
STAGE_BRANCH="stage/compile-break"   # the Slide 3 prop branch — never merged
DISPLAY_PORT=8082                    # orders-backend for the Order Detail panel. NOT 8080:
                                     # mvn verify starts its own copy on 8080 to write the spec.
DASHBOARD_ORIGIN="http://localhost:4200"

if [ -t 1 ]; then
  C_OK=$'\033[32m'; C_BAD=$'\033[31m'; C_WARN=$'\033[33m'; C_H=$'\033[1;36m'; C_OFF=$'\033[0m'
else
  C_OK=''; C_BAD=''; C_WARN=''; C_H=''; C_OFF=''
fi

say()  { printf '\n%s=== %s%s\n' "$C_H" "$*" "$C_OFF"; }
ok()   { printf '  %s✔%s %s\n' "$C_OK" "$C_OFF" "$*"; }
bad()  { printf '  %s✘%s %s\n' "$C_BAD" "$C_OFF" "$*"; }
warn() { printf '  %s!%s %s\n' "$C_WARN" "$C_OFF" "$*"; }
die()  { printf '%sERROR:%s %s\n' "$C_BAD" "$C_OFF" "$*" >&2; exit 1; }

need() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || die "'$c' is not installed or not on PATH"
  done
}

# The platform the dashboard points at is the platform every script talks to.
# One source of truth: orders-frontend/public/config.js, written by demo/target.sh.
platform_url() {
  sed -n "s/^[[:space:]]*platformBase:[[:space:]]*'\([^']*\)'.*/\1/p" "$CONFIG_JS" | head -1 | sed 's#/*$##'
}

orders_url() {
  sed -n "s/^[[:space:]]*ordersBase:[[:space:]]*'\([^']*\)'.*/\1/p" "$CONFIG_JS" | head -1 | sed 's#/*$##'
}

current_branch() { git -C "$ROOT" rev-parse --abbrev-ref HEAD; }

port_pid() { lsof -ti "tcp:$1" -sTCP:LISTEN 2>/dev/null | head -1 || true; }

# HTTP status code only; 000 when unreachable. Never fails the calling script.
http_code() {
  curl -s -o /dev/null -w '%{http_code}' --max-time "${2:-8}" "$1" 2>/dev/null || true
}

# Seconds since the epoch — portable timer for "how long did that take".
now() { date +%s; }
