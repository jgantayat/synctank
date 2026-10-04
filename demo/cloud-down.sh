#!/usr/bin/env bash
# Day 13 — scale the cloud platform to zero between rehearsals (~₹100/day saved).
# The ALB stays (~₹47/day) because it owns the DNS name the dashboard and slides use.
# The registry and agent audit log are lost with the task; cloud-up.sh re-seeds them.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need aws
: "${AWS_REGION:?source infra/aws/deploy/00-env.sh first}" "${CLUSTER:?}" "${SERVICE:?}"
export AWS_PAGER=""

aws ecs update-service --region "$AWS_REGION" --cluster "$CLUSTER" --service "$SERVICE" \
  --desired-count 0 >/dev/null

# Verify rather than assume — a mangled paste once left the service running all night (Day 11).
aws ecs describe-services --region "$AWS_REGION" --cluster "$CLUSTER" --services "$SERVICE" \
  --query 'services[0].{desired:desiredCount,running:runningCount}' --output table
echo "desired must be 0. running drops to 0 within about a minute."
echo "The synctank-platform-unhealthy alarm will email you — expected while scaled to zero."
