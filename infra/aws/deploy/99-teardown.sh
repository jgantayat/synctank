#!/usr/bin/env bash
# Day 11 — delete everything this day created, cheapest-first so a partial run still saves
# the most money. Leaves the S3 bucket and the secret alone: those hold state you may want,
# and neither costs anything meaningful. Delete them by hand when you are truly done.
set -euo pipefail
: "${AWS_REGION:?source 00-env.sh first}"

echo "=== scale the service to zero (this is ~70% of the cost, and it is reversible)"
aws ecs update-service --region "$AWS_REGION" --cluster "$CLUSTER" --service "$SERVICE" \
  --desired-count 0 >/dev/null 2>&1 || true

read -rp "Delete the service, ALB and target group too? [y/N] " confirm
[ "$confirm" = "y" ] || { echo "Stopped at desired-count 0. Resume with 30-deploy.sh."; exit 0; }

aws ecs delete-service --region "$AWS_REGION" --cluster "$CLUSTER" --service "$SERVICE" --force >/dev/null 2>&1 || true
LISTENER=$(aws elbv2 describe-listeners --region "$AWS_REGION" --load-balancer-arn \
  "$(aws elbv2 describe-load-balancers --region "$AWS_REGION" --names synctank-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)" \
  --query 'Listeners[0].ListenerArn' --output text 2>/dev/null || true)
[ -n "${LISTENER:-}" ] && [ "$LISTENER" != "None" ] && aws elbv2 delete-listener --region "$AWS_REGION" --listener-arn "$LISTENER" || true
aws elbv2 delete-load-balancer --region "$AWS_REGION" --load-balancer-arn \
  "$(aws elbv2 describe-load-balancers --region "$AWS_REGION" --names synctank-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)" 2>/dev/null || true
sleep 20
aws elbv2 delete-target-group --region "$AWS_REGION" --target-group-arn "$TG_ARN" 2>/dev/null || true
echo "done. Bucket, secret, ECR repo, log group and IAM roles are deliberately left in place."