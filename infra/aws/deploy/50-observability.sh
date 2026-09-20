#!/usr/bin/env bash
# Day 11 — alarms and a budget. Three things can go wrong and each gets exactly one alarm:
#   1. the platform is down          -> ALB HealthyHostCount < 1
#   2. the platform is erroring      -> ERROR lines in the log group
#   3. the platform is spending      -> AI_CALL lines in the log group
#
# METRIC FILTERS USE PLAIN-TEXT PATTERNS, NOT JSON SELECTORS, on purpose: a JSON selector
# would have to name a field in the structured-logging format, and CloudWatch's selector
# syntax cannot address a literal dotted key like "log.level". A text pattern matches
# whether logs are structured or plain, so §4.3's fallback costs nothing here.
set -euo pipefail
: "${AWS_REGION:?source 00-env.sh first}" "${LOG_GROUP:?}" "${ALERT_EMAIL:?}" "${TG_ARN:?}"

echo "=== SNS topic + email subscription"
TOPIC_ARN=$(aws sns create-topic --region "$AWS_REGION" --name synctank-alerts --query TopicArn --output text)
aws sns subscribe --region "$AWS_REGION" --topic-arn "$TOPIC_ARN" \
  --protocol email --notification-endpoint "$ALERT_EMAIL" >/dev/null
echo "ok: $TOPIC_ARN  — CONFIRM THE EMAIL or no alarm will ever reach you"

echo "=== metric filters"
aws logs put-metric-filter --region "$AWS_REGION" \
  --log-group-name "$LOG_GROUP" --filter-name synctank-errors \
  --filter-pattern '"ERROR"' \
  --metric-transformations metricName=PlatformErrors,metricNamespace=SyncTank,metricValue=1,defaultValue=0

aws logs put-metric-filter --region "$AWS_REGION" \
  --log-group-name "$LOG_GROUP" --filter-name synctank-ai-calls \
  --filter-pattern '"AI_CALL"' \
  --metric-transformations metricName=AiCalls,metricNamespace=SyncTank,metricValue=1,defaultValue=0
echo "ok: SyncTank/PlatformErrors, SyncTank/AiCalls"

echo "=== alarms"
aws cloudwatch put-metric-alarm --region "$AWS_REGION" \
  --alarm-name synctank-platform-errors \
  --alarm-description "5+ ERROR log lines in 5 minutes" \
  --namespace SyncTank --metric-name PlatformErrors --statistic Sum \
  --period 300 --evaluation-periods 1 --threshold 5 \
  --comparison-operator GreaterThanOrEqualToThreshold --treat-missing-data notBreaching \
  --alarm-actions "$TOPIC_ARN"

# Fires WELL BELOW the in-process cap of 200/day. The cap is the stop; this is the warning
# that something is looping, while there is still room to look before it stops.
aws cloudwatch put-metric-alarm --region "$AWS_REGION" \
  --alarm-name synctank-ai-call-volume \
  --alarm-description "More than 50 AI calls in an hour — a loop, not a demo" \
  --namespace SyncTank --metric-name AiCalls --statistic Sum \
  --period 3600 --evaluation-periods 1 --threshold 50 \
  --comparison-operator GreaterThanThreshold --treat-missing-data notBreaching \
  --alarm-actions "$TOPIC_ARN"

TG_NAME=$(aws elbv2 describe-target-groups --region "$AWS_REGION" --target-group-arns "$TG_ARN" \
  --query 'TargetGroups[0].TargetGroupArn' --output text | awk -F: '{print $NF}')
LB_DIM=$(aws elbv2 describe-load-balancers --region "$AWS_REGION" --names synctank-alb \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text | sed 's#.*:loadbalancer/##')
aws cloudwatch put-metric-alarm --region "$AWS_REGION" \
  --alarm-name synctank-platform-unhealthy \
  --alarm-description "No healthy target behind the ALB for 2 minutes" \
  --namespace AWS/ApplicationELB --metric-name HealthyHostCount --statistic Minimum \
  --period 60 --evaluation-periods 2 --threshold 1 \
  --comparison-operator LessThanThreshold --treat-missing-data breaching \
  --dimensions Name=TargetGroup,Value="$TG_NAME" Name=LoadBalancer,Value="$LB_DIM" \
  --alarm-actions "$TOPIC_ARN"
echo "ok: 3 alarms"

echo "=== monthly AWS budget (INFRASTRUCTURE ONLY — see the note below)"
cat > /tmp/budget.json <<EOF
{ "BudgetName": "synctank-monthly",
  "BudgetLimit": { "Amount": "${BUDGET_USD}", "Unit": "USD" },
  "TimeUnit": "MONTHLY", "BudgetType": "COST" }
EOF
cat > /tmp/budget-notifications.json <<EOF
[ { "Notification": { "NotificationType": "FORECASTED", "ComparisonOperator": "GREATER_THAN",
      "Threshold": 80, "ThresholdType": "PERCENTAGE" },
    "Subscribers": [ { "SubscriptionType": "EMAIL", "Address": "${ALERT_EMAIL}" } ] } ]
EOF
aws budgets create-budget --account-id "$AWS_ACCOUNT_ID" \
  --budget file:///tmp/budget.json \
  --notifications-with-subscribers file:///tmp/budget-notifications.json 2>/dev/null \
  && echo "ok: \$${BUDGET_USD}/month budget, alert at 80% forecast" \
  || echo "budget already exists — skipping"

cat <<'NOTE'

--------------------------------------------------------------------------
The AWS budget above does NOT cover the platform's model spend.
contract-platform calls api.anthropic.com directly (spring-ai-starter-model-anthropic),
not Bedrock, so that spend is billed by Anthropic and is structurally invisible to AWS
Budgets, Cost Explorer and every CloudWatch billing metric. Audit finding F5.

Model spend has exactly two controls, and both are outside AWS:
  1. In-process: platform.ai.daily-call-limit (AI_DAILY_CALL_LIMIT), default 200/day,
     enforced by AiUsageMeter. This is the hard stop. Check it: GET /health/ai
  2. Account-level: set a monthly spend limit in the Anthropic console. Do this now if
     you have not — it is the only thing that survives a compromised key.
Say this out loud on the cost slide. A judge who knows the difference between Bedrock
and a direct API call will ask, and "we knew, here is the guardrail" is the right answer.
--------------------------------------------------------------------------
NOTE