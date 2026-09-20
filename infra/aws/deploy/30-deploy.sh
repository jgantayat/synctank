#!/usr/bin/env bash
# Day 11 — render the task definition, register it, and create or update the service.
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${AWS_REGION:?source 00-env.sh first}" "${PLATFORM_SECRET_ARN:?}" "${TG_ARN:?}" "${TASK_SG:?}"

echo "=== render task definition"
envsubst '${AWS_ACCOUNT_ID} ${AWS_REGION} ${SPEC_BUCKET} ${IMAGE_TAG} ${DASHBOARD_ORIGIN} ${PLATFORM_SECRET_ARN} ${AI_DAILY_CALL_LIMIT}' \
  < infra/aws/ecs/contract-platform-task-definition.json > /tmp/taskdef.json
python3 -m json.tool /tmp/taskdef.json >/dev/null || { echo "rendered task definition is not valid JSON"; exit 1; }
grep -q '\${' /tmp/taskdef.json && { echo "ERROR: unrendered placeholder left in the task definition:"; grep -n '\${' /tmp/taskdef.json; exit 1; }

echo "=== register"
TD_ARN=$(aws ecs register-task-definition --region "$AWS_REGION" \
  --cli-input-json file:///tmp/taskdef.json \
  --query 'taskDefinition.taskDefinitionArn' --output text)
echo "ok: $TD_ARN"

SUBNET_CSV=$(echo "$SUBNET_IDS" | tr ' ' ',')

if aws ecs describe-services --region "$AWS_REGION" --cluster "$CLUSTER" --services "$SERVICE" \
     --query 'services[0].status' --output text 2>/dev/null | grep -q ACTIVE; then
  echo "=== update existing service"
  aws ecs update-service --region "$AWS_REGION" --cluster "$CLUSTER" --service "$SERVICE" \
    --task-definition "$TD_ARN" --desired-count 1 --force-new-deployment >/dev/null
else
  echo "=== create service"
  aws ecs create-service --region "$AWS_REGION" --cluster "$CLUSTER" --service-name "$SERVICE" \
    --task-definition "$TD_ARN" --desired-count 1 --launch-type FARGATE \
    --network-configuration "awsvpcConfiguration={subnets=[$SUBNET_CSV],securityGroups=[$TASK_SG],assignPublicIp=ENABLED}" \
    --load-balancers "targetGroupArn=$TG_ARN,containerName=contract-platform,containerPort=8081" \
    --health-check-grace-period-seconds 120 >/dev/null
fi

echo "=== waiting for the service to stabilise (up to ~5 minutes)"
aws ecs wait services-stable --region "$AWS_REGION" --cluster "$CLUSTER" --services "$SERVICE"
echo "stable. Platform URL: ${PLATFORM_URL:-http://$ALB_DNS}"