#!/usr/bin/env bash
# Day 13 — Layer 3 of demo insurance: the whole platform on this laptop, no AWS involved.
#
#   MinIO (S3) + Postgres + LocalStack (Secrets Manager) + contract-platform container on :8081
#
# Needs no AWS credentials and no ALB. Still needs the internet for the parts that are
# internet by nature: the agent's model call (api.anthropic.com) and its PR (api.github.com).
# Slide 3's compile break needs no network at all.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need docker curl jq git
cd "$ROOT" || exit 1

MINIO_IMAGE_DEFAULT="quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z"
MINIO_BACKUP="$HOME/synctank-minio.tar.gz"

[ "$(current_branch)" = "main" ] || die "run this on main (you are on $(current_branch)) — the seed stamps the baseline with HEAD's sha"
docker info >/dev/null 2>&1 || die "Docker is not running. Start Docker Desktop and retry."

say "1/5 MinIO image (quay.io refuses anonymous pulls since Sept 2026)"
if docker image inspect "${MINIO_IMAGE:-$MINIO_IMAGE_DEFAULT}" >/dev/null 2>&1; then
  ok "cached: ${MINIO_IMAGE:-$MINIO_IMAGE_DEFAULT}"
elif [ -f "$MINIO_BACKUP" ]; then
  echo "not cached — restoring from $MINIO_BACKUP"
  gunzip -c "$MINIO_BACKUP" | docker load
else
  die "MinIO image is neither cached nor backed up at $MINIO_BACKUP. See the comment in docker-compose.yml."
fi

say "2/5 local secret file"
[ -f infra/localstack/secrets/secrets.local.json ] \
  || die "infra/localstack/secrets/secrets.local.json is missing — see infra/localstack/secrets/README.md"
jq -e 'has("dbPassword") and has("s3AccessKey") and has("s3SecretKey")' \
  infra/localstack/secrets/secrets.local.json >/dev/null \
  || die "secrets.local.json lacks dbPassword/s3AccessKey/s3SecretKey"
ok "present, required keys found (values not printed)"

say "3/5 port 8081"
HOLDER="$(port_pid 8081)"
if [ -n "$HOLDER" ] && ! docker compose --profile platform ps --status running contract-platform 2>/dev/null | grep -q contract-platform; then
  die "port 8081 is held by pid $HOLDER (a contract-platform started with mvn?). Stop it first."
fi
ok "free, or already the compose container"

say "4/5 start the stack (first run builds the platform image: ~3-5 minutes)"
docker compose up -d minio postgres localstack
docker compose --profile platform up -d --build contract-platform
i=0
until [ "$(http_code http://localhost:8081/health/ready 5)" = "200" ]; do
  i=$((i + 1))
  [ $i -le 60 ] || { docker compose --profile platform logs --tail 40 contract-platform; die "not ready after 5 minutes"; }
  sleep 5
done
curl -s http://localhost:8081/health/ready | jq -c '{ready, checks: [.checks[] | {name, ok}]}'

say "5/5 demo data"
APPS="$(curl -s http://localhost:8081/registry/apps | jq 'length')"
if [ "$APPS" = "0" ] || ! curl -s "http://localhost:8081/specs/$SPEC_KEY/history" | jq -e 'length > 0' >/dev/null; then
  PLATFORM_URL=http://localhost:8081 bash infra/aws/deploy/40-seed-demo-data.sh
else
  echo "registry holds $APPS app(s) and specs exist — not re-seeding (local Postgres keeps its data)"
fi

say "local platform ready: http://localhost:8081"
echo "Next: bash demo/target.sh local && bash demo/preflight.sh local"
