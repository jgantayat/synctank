#!/usr/bin/env bash
# Day 11 — put the deployed platform into demo-ready state.
#
# WHY THIS EXISTS: the cloud instance has its own S3 bucket and its own (ephemeral) Postgres.
# CI's pipeline runs its own throwaway platform inside the GitHub runner and writes to local
# MinIO, so nothing CI does ever lands here. Without this script the deployed dashboard shows
# an empty timeline and empty radar charts — technically correct, useless on a stage.
#
# RUN THIS AFTER EVERY TASK REPLACEMENT. The sidecar database is ephemeral by design
# (decision D2); the specs in S3 survive, the registry and agent audit log do not.
#
# Everything it seeds is the same data CI seeds, from the same committed baseline file, and
# the call volumes are the same SEEDED (not measured) figures the pitch already labels as such.
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${PLATFORM_URL:?source 00-env.sh first}"

BASELINE=orders-backend/contract/openapi.baseline.json
[ -f "$BASELINE" ] || { echo "No committed baseline at $BASELINE"; exit 1; }
SHA=$(git rev-parse HEAD)

echo "=== 1/4 upload the committed baseline as a spec version"
curl -fS -X PUT -H 'Content-Type: application/json' \
  --data-binary "@$BASELINE" "$PLATFORM_URL/specs/orders-backend/$SHA"

echo "=== 2/4 publish it as the baseline pointer"
curl -fS -X PUT -H 'Content-Type: text/plain' --data "$SHA" \
  "$PLATFORM_URL/specs/orders-backend/baseline"

echo "=== 3/4 register customer-portal, deriving usage from the frontend source"
# /workspace/orders-frontend/src is the path the init container extracted to (F3). This is
# the cloud equivalent of docker-compose's bind mount, and the reason the task has an init
# container at all.
jq -n --rawfile spec "$BASELINE" --arg version "$SHA" \
  '{appName:"customer-portal", team:"Team Checkout", repo:"jgantayat/synctank",
    clientVersion:$version, frontendSrcPath:"/workspace/orders-frontend/src",
    baselineSpec:$spec}' > /tmp/seed.json
curl -fS -X POST "$PLATFORM_URL/registry/apps" -H 'Content-Type: application/json' \
  --data-binary @/tmp/seed.json | jq .

echo "=== 4/4 seed the demo telemetry (SEEDED, NOT MEASURED — say so on the slide)"
curl -fS -X PUT "$PLATFORM_URL/registry/apps/customer-portal/traffic" \
  -H 'Content-Type: application/json' \
  -d '{"GET /api/orders/{id}": 12000, "GET /api/orders": 3400, "POST /api/orders": 800}' | jq .

echo
echo "=== state check"
curl -s "$PLATFORM_URL/specs/orders-backend/history?limit=5" | jq '[.[] | {commit: .commit[0:7], endpointCount, schemaCount, baseline}]'
curl -s "$PLATFORM_URL/registry/apps" | jq .