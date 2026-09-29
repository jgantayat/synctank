#!/usr/bin/env bash
#
# SyncTank contract-check — step 1: a throwaway contract-platform on this runner.
#
# Exactly what contract.yml did inline from Day 02 to Day 11, with three differences:
#   - the spec store and database are started with `docker run`, not the CALLER's
#     docker-compose.yml, because an adopting repository does not have ours;
#   - host ports are high and unusual (19000 / 15433 / 18081) so they cannot collide with
#     services the caller's own job already runs on 9000 / 5432 / 8080 / 8081;
#   - the Contract Agent is switched OFF and GITHUB_TOKEN is blanked for the process. A CI
#     platform only ever diffs; it has no business holding a credential that can open PRs.
#
# Idempotent within a job: a second `uses:` of this action (a repo with two APIs) finds the
# platform already answering and reuses it.
set -euo pipefail
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

: "${ACTION_PATH:?ACTION_PATH must point at the action directory}"

PORT="${PLATFORM_PORT:-18081}"
PLATFORM_URL="http://localhost:${PORT}"
MINIO_PORT=19000
PG_PORT=15433
# Same pin as docker-compose.yml, for the same reason (Day 10: Docker Hub withdrew minio/minio).
MINIO_IMAGE="quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z"
PG_IMAGE="postgres:16"

ROOT="$(work_root)"
mkdir -p "$ROOT"
LOG="$ROOT/platform.log"

if curl -sf "$PLATFORM_URL/health" > /dev/null 2>&1; then
  echo "contract-platform already answering on $PLATFORM_URL — reusing it."
  set_output url "$PLATFORM_URL"
  set_output log "$LOG"
  exit 0
fi

group "Usage scanner (ripgrep)"
# Not preinstalled on ubuntu-latest (Day 05). Without it the registry seed returns 503 (Day 10 F1).
if ! command -v rg > /dev/null 2>&1; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq ripgrep
fi
rg --version | head -n 1
endgroup

group "Spec store (MinIO) and registry database (Postgres)"
docker rm -f synctank-cc-minio synctank-cc-postgres > /dev/null 2>&1 || true

docker run -d --name synctank-cc-minio -p "${MINIO_PORT}:9000" \
  -e MINIO_ROOT_USER=platform -e MINIO_ROOT_PASSWORD=platform123 \
  "$MINIO_IMAGE" server /data > /dev/null

docker run -d --name synctank-cc-postgres -p "${PG_PORT}:5432" \
  -e POSTGRES_USER=platform -e POSTGRES_PASSWORD=platform123 -e POSTGRES_DB=contract_platform \
  "$PG_IMAGE" > /dev/null

wait_for "http://localhost:${MINIO_PORT}/minio/health/live" MinIO 30 \
  || { docker logs synctank-cc-minio | tail -n 40; exit 1; }

PG_OK=0
for i in $(seq 1 30); do
  if docker exec synctank-cc-postgres pg_isready -U platform -d contract_platform > /dev/null 2>&1; then
    echo "Postgres is up (attempt $i)"; PG_OK=1; break
  fi
  sleep 2
done
if [ "$PG_OK" != 1 ]; then
  echo "::error title=Contract check::Postgres never became ready"
  docker logs synctank-cc-postgres | tail -n 40
  exit 1
fi
endgroup

group "Build contract-platform"
# The action directory is .github/actions/contract-check inside a full copy of the synctank
# repository — for `uses: ./...` it is the caller's checkout, for `uses: jgantayat/synctank/...@v1`
# it is the copy GitHub downloaded at that tag. Either way the platform source is three levels up.
PLATFORM_DIR="$(cd "$ACTION_PATH/../../../contract-platform" && pwd)"
(cd "$PLATFORM_DIR" && mvn -B -q -ntp -DskipTests package)
JAR="$(find "$PLATFORM_DIR/target" -maxdepth 1 -name 'contract-platform-*.jar' ! -name '*-plain.jar' | head -n 1)"
[ -n "$JAR" ] || { echo "::error title=Contract check::no contract-platform jar was built"; exit 1; }
echo "Built $JAR"
endgroup

group "Start contract-platform on :$PORT"
# ${ANTHROPIC_API_KEY:-not-configured}: an EMPTY env var is a present-but-blank property to Spring,
# which skips application.yaml's own default and hands Spring AI a blank key (Day 09 F3).
SERVER_PORT="$PORT" \
SECRETS_PROVIDER=env \
S3_ENDPOINT="http://localhost:${MINIO_PORT}" \
S3_ACCESS_KEY=platform \
S3_SECRET_KEY=platform123 \
S3_BUCKET=specs \
DB_URL="jdbc:postgresql://localhost:${PG_PORT}/contract_platform" \
DB_USER=platform \
DB_PASSWORD=platform123 \
AGENT_ENABLED=false \
GITHUB_TOKEN="" \
ANTHROPIC_API_KEY="${ANTHROPIC_API_KEY:-not-configured}" \
  nohup java -jar "$JAR" > "$LOG" 2>&1 &

# /health/ready (Day 11), not /health: /health answers as soon as Tomcat serves, which is BEFORE
# BucketInitializer has created the bucket. Ready means database valid AND bucket reachable.
if ! wait_for "$PLATFORM_URL/health/ready" contract-platform 60; then
  tail -n 80 "$LOG"
  exit 1
fi
curl -sS "$PLATFORM_URL/health/ready"; echo
endgroup

set_output url "$PLATFORM_URL"
set_output log "$LOG"