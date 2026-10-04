#!/usr/bin/env bash
# Day 13 — bring the cloud platform back from zero, and make it demo-ready.
#
# Run from the repo root, on `main`, in a terminal where infra/aws/deploy/00-env.sh is sourced.
#
# WHY NOT 30-deploy.sh: 00-env.sh sets IMAGE_TAG=$(git rev-parse --short HEAD). The moment
# `main` moves past the commit whose image is in ECR (today's demo-kit merge does exactly
# that), 30-deploy.sh renders a task definition for an image that does not exist and the
# service crash-loops. Scaling the service reuses the task definition already registered,
# which names the image that IS in ECR. 30-deploy.sh is for shipping new platform code only.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need aws curl jq git
: "${AWS_REGION:?source infra/aws/deploy/00-env.sh first}" "${CLUSTER:?}" "${SERVICE:?}" "${PLATFORM_URL:?}" "${ALB_SG:?}"
export AWS_PAGER=""
cd "$ROOT" || exit 1

[ "$(current_branch)" = "main" ] \
  || die "run this on main (you are on $(current_branch)). 40-seed-demo-data.sh stamps the cloud baseline with HEAD's sha, and a demo branch sha is not a commit on main."

say "1/5 allow this network's IP on the load balancer"
bash infra/aws/deploy/15-allow-my-ip.sh

say "2/5 service state"
read -r DESIRED RUNNING TASKDEF <<<"$(aws ecs describe-services --region "$AWS_REGION" --cluster "$CLUSTER" \
  --services "$SERVICE" --query 'services[0].[desiredCount,runningCount,taskDefinition]' --output text)"
IMAGE="$(aws ecs describe-task-definition --region "$AWS_REGION" --task-definition "$TASKDEF" \
  --query "taskDefinition.containerDefinitions[?name=='contract-platform'].image | [0]" --output text)"
echo "desired=$DESIRED running=$RUNNING"
echo "task definition: ${TASKDEF##*/}"
echo "image tag:       ${IMAGE##*:}"

say "3/5 scale to 1"
if [ "$DESIRED" = "1" ] && [ "$RUNNING" = "1" ]; then
  echo "already running — nothing to scale"
else
  aws ecs update-service --region "$AWS_REGION" --cluster "$CLUSTER" --service "$SERVICE" \
    --desired-count 1 >/dev/null
  echo "waiting for the service to stabilise (up to ~5 minutes) ..."
  aws ecs wait services-stable --region "$AWS_REGION" --cluster "$CLUSTER" --services "$SERVICE"
fi

say "4/5 wait for /health/ready through the load balancer"
# services-stable can report success inside a crash loop's healthy window (Day 11). The only
# answer that counts is the platform itself saying its database and bucket are reachable.
i=0
until [ "$(http_code "$PLATFORM_URL/health/ready" 5)" = "200" ]; do
  i=$((i + 1))
  [ $i -le 36 ] || die "not ready after 3 minutes. Check: aws logs tail ${LOG_GROUP:-/ecs/synctank/contract-platform} --since 10m --region $AWS_REGION"
  sleep 5
done
curl -s "$PLATFORM_URL/health/ready" | jq -c '{ready, checks: [.checks[] | {name, ok}]}'

say "5/5 demo data"
APPS="$(curl -s --max-time 10 "$PLATFORM_URL/registry/apps" | jq 'length')"
if [ "$APPS" = "0" ]; then
  echo "registry is empty (fresh task — its database is ephemeral by design): seeding"
  bash infra/aws/deploy/40-seed-demo-data.sh
else
  echo "registry already holds $APPS app(s) — not re-seeding"
fi

say "cloud platform ready: $PLATFORM_URL"
echo "Next: bash demo/target.sh cloud && bash demo/preflight.sh cloud"
