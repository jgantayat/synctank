#!/usr/bin/env bash
#
# SyncTank contract-check — step 1: a throwaway contract-platform on this runner.
#
# Exactly what contract.yml did inline from Day 02 to Day 11, with four differences:
#   - the spec store and database are started with `docker run`, not the CALLER's
#     docker-compose.yml, because an adopting repository does not have ours;
#   - the S3 store is LocalStack, not MinIO. MinIO withdrew its images from Docker Hub
#     (2026-09-11) and then closed quay.io to anonymous pulls too (seen on PR #22: "unauthorized").
#     LocalStack's S3 is the same AWS API, the image is public on Docker Hub, and this project
#     already depends on it for Secrets Manager (Day 09). The platform only sees "an S3 endpoint".
#   - host ports are high and unusual (19000 / 15433 / 18081) so they cannot collide with
#     services the caller's own job (or your own LocalStack on 4566) already uses;
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
S3_PORT=19000
PG_PORT=15433
# Same tag docker-compose.yml already uses for LocalStack. Public on Docker Hub, no login.
S3_IMAGE="localstack/localstack:3"
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

group "Spec store (LocalStack S3) and registry database (Postgres)"
# synctank-cc-minio is removed too, in case a runner or laptop still has one from an earlier version.
docker rm -f synctank-cc-s3 synctank-cc-minio synctank-cc-postgres > /dev/null 2>&1 || true

docker run -d --name synctank-cc-s3 -p "${S3_PORT}:4566" \
  -e SERVICES=s3 -e AWS_DEFAULT_REGION=us-east-1 \
  "$S3_IMAGE" > /dev/null

docker run -d --name synctank-cc-postgres -p "${PG_PORT}:5432" \
  -e POSTGRES_USER=platform -e POSTGRES_PASSWORD=platform123 -e POSTGRES_DB=contract_platform \
  "$PG_IMAGE" > /dev/null

# /_localstack/health answers before S3 is usable; wait until it reports s3 available or running.
S3_OK=0
for i in $(seq 1 45); do
  if curl -sf "http://localhost:${S3_PORT}/_localstack/health" 2> /dev/null \
       | grep -Eq '"s3": ?"(available|running)"'; then
    echo "LocalStack S3 is up (attempt $i)"; S3_OK=1; break
  fi
  sleep 2
done
if [ "$S3_OK" != 1 ]; then
  echo "::error title=Contract check::LocalStack S3 never became available"
  docker logs synctank-cc-s3 | tail -n 40
  exit 1
fi

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
S3_ENDPOINT="http://localhost:${S3_PORT}" \
S3_ACCESS_KEY=test \
S3_SECRET_KEY=test \
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