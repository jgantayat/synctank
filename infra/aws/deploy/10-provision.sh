#!/usr/bin/env bash
# Day 11 — create everything that is not the application: registry, bucket, secret, logs,
# roles, network, load balancer, cluster. Idempotent: safe to re-run after a partial failure.
#
# Applies the IAM documents written on Day 10 (infra/aws/iam/) rather than inventing new ones.
set -euo pipefail
cd "$(dirname "$0")/../../.."   # repo root

: "${AWS_REGION:?source infra/aws/deploy/00-env.sh first}"
: "${AWS_ACCOUNT_ID:?}" "${SPEC_BUCKET:?}" "${DEMO_CIDR:?}"

say() { printf '\n=== %s\n' "$*"; }

say "ECR repository"
aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 \
  || aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION" \
       --image-scanning-configuration scanOnPush=true >/dev/null
echo "ok: $ECR_REPO"

say "S3 spec bucket (private, versioned)"
if ! aws s3api head-bucket --bucket "$SPEC_BUCKET" 2>/dev/null; then
  # us-east-1 is the one region that rejects a LocationConstraint. Everywhere else requires it.
  if [ "$AWS_REGION" = "us-east-1" ]; then
    aws s3api create-bucket --bucket "$SPEC_BUCKET" --region "$AWS_REGION" >/dev/null
  else
    aws s3api create-bucket --bucket "$SPEC_BUCKET" --region "$AWS_REGION" \
      --create-bucket-configuration LocationConstraint="$AWS_REGION" >/dev/null
  fi
fi
aws s3api put-public-access-block --bucket "$SPEC_BUCKET" \
  --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
# Versioning: a spec object is keyed by commit and never overwritten, but BASELINE is a
# mutable pointer. Versioning makes "what was the baseline yesterday?" answerable.
aws s3api put-bucket-versioning --bucket "$SPEC_BUCKET" \
  --versioning-configuration Status=Enabled
echo "ok: s3://$SPEC_BUCKET"

say "Secrets Manager"
SECRET_FILE=infra/aws/deploy/secrets.local.json
if [ ! -f "$SECRET_FILE" ]; then
  echo "ERROR: $SECRET_FILE not found. Create it (gitignored by the repo's secrets.local.json"
  echo "rule) with ROTATED values:"
  echo '  { "dbPassword": "...", "anthropicApiKey": "sk-ant-...", "githubToken": "github_pat_..." }'
  echo "s3AccessKey/s3SecretKey are NOT needed here: S3_AUTH=iam means the task role grants S3."
  exit 1
fi
python3 -m json.tool "$SECRET_FILE" >/dev/null || { echo "ERROR: $SECRET_FILE is not valid JSON"; exit 1; }
if aws secretsmanager describe-secret --secret-id "$SECRET_ID" --region "$AWS_REGION" >/dev/null 2>&1; then
  aws secretsmanager put-secret-value --secret-id "$SECRET_ID" --region "$AWS_REGION" \
    --secret-string "file://$SECRET_FILE" --query Name --output text
else
  aws secretsmanager create-secret --name "$SECRET_ID" --region "$AWS_REGION" \
    --description "SyncTank contract-platform credentials" \
    --secret-string "file://$SECRET_FILE" --query Name --output text
fi
PLATFORM_SECRET_ARN=$(aws secretsmanager describe-secret --secret-id "$SECRET_ID" \
  --region "$AWS_REGION" --query ARN --output text)
echo "ok: $PLATFORM_SECRET_ARN"

say "CloudWatch log group (7-day retention — cost control, not policy)"
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || true
aws logs put-retention-policy --log-group-name "$LOG_GROUP" --retention-in-days 7 --region "$AWS_REGION"
echo "ok: $LOG_GROUP"

say "IAM roles (from Day 10's policy documents)"
render() { envsubst '${AWS_ACCOUNT_ID} ${AWS_REGION} ${SPEC_BUCKET}' < "$1" > "$2"; }
render infra/aws/iam/ecs-tasks-trust-policy.json            /tmp/trust.json
render infra/aws/iam/contract-platform-task-role-policy.json /tmp/task-role.json
render infra/aws/iam/ecs-task-execution-role-policy.json     /tmp/exec-role.json

ensure_role() {  # $1 role name, $2 trust file, $3 policy name, $4 policy file
  aws iam get-role --role-name "$1" >/dev/null 2>&1 \
    || aws iam create-role --role-name "$1" --assume-role-policy-document "file://$2" >/dev/null
  aws iam put-role-policy --role-name "$1" --policy-name "$3" --policy-document "file://$4"
  echo "ok: role $1"
}
ensure_role synctank-contract-platform-task      /tmp/trust.json synctank-task-policy      /tmp/task-role.json
ensure_role synctank-contract-platform-execution /tmp/trust.json synctank-execution-policy /tmp/exec-role.json

say "Network: default VPC, its default subnets, two security groups"
VPC_ID=$(aws ec2 describe-vpcs --region "$AWS_REGION" \
  --filters Name=isDefault,Values=true --query 'Vpcs[0].VpcId' --output text)
SUBNET_IDS=$(aws ec2 describe-subnets --region "$AWS_REGION" \
  --filters Name=vpc-id,Values="$VPC_ID" Name=default-for-az,Values=true \
  --query 'Subnets[].SubnetId' --output text | tr '\t' ' ')
echo "vpc=$VPC_ID subnets=$SUBNET_IDS"

sg_id() { aws ec2 describe-security-groups --region "$AWS_REGION" \
  --filters Name=group-name,Values="$1" Name=vpc-id,Values="$VPC_ID" \
  --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null; }

ALB_SG=$(sg_id synctank-alb-sg)
if [ "$ALB_SG" = "None" ] || [ -z "$ALB_SG" ]; then
  ALB_SG=$(aws ec2 create-security-group --region "$AWS_REGION" --group-name synctank-alb-sg \
    --description "SyncTank ALB - allowlisted demo access only" --vpc-id "$VPC_ID" \
    --query GroupId --output text)
fi
# F1: the platform has no authentication and can open pull requests. This allowlist is the
# access control. Re-run 15-allow-my-ip.sh from a new network instead of widening it.
aws ec2 authorize-security-group-ingress --region "$AWS_REGION" --group-id "$ALB_SG" \
  --protocol tcp --port 80 --cidr "$DEMO_CIDR" 2>/dev/null || true
echo "ok: alb sg $ALB_SG, ingress 80 from $DEMO_CIDR"

TASK_SG=$(sg_id synctank-task-sg)
if [ "$TASK_SG" = "None" ] || [ -z "$TASK_SG" ]; then
  TASK_SG=$(aws ec2 create-security-group --region "$AWS_REGION" --group-name synctank-task-sg \
    --description "SyncTank Fargate task - ALB only" --vpc-id "$VPC_ID" \
    --query GroupId --output text)
fi
aws ec2 authorize-security-group-ingress --region "$AWS_REGION" --group-id "$TASK_SG" \
  --protocol tcp --port 8081 --source-group "$ALB_SG" 2>/dev/null || true
echo "ok: task sg $TASK_SG, ingress 8081 from the ALB only"

say "Application Load Balancer + target group"
ALB_ARN=$(aws elbv2 describe-load-balancers --region "$AWS_REGION" --names synctank-alb \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text 2>/dev/null || true)
if [ -z "$ALB_ARN" ] || [ "$ALB_ARN" = "None" ]; then
  ALB_ARN=$(aws elbv2 create-load-balancer --region "$AWS_REGION" --name synctank-alb \
    --type application --scheme internet-facing \
    --subnets $SUBNET_IDS --security-groups "$ALB_SG" \
    --query 'LoadBalancers[0].LoadBalancerArn' --output text)
fi

TG_ARN=$(aws elbv2 describe-target-groups --region "$AWS_REGION" --names synctank-platform-tg \
  --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || true)
if [ -z "$TG_ARN" ] || [ "$TG_ARN" = "None" ]; then
  # target-type ip: Fargate awsvpc tasks register by ENI address, not instance id.
  # Health check on /health, NOT /health/ready — see the guide's decision D4.
  TG_ARN=$(aws elbv2 create-target-group --region "$AWS_REGION" --name synctank-platform-tg \
    --protocol HTTP --port 8081 --vpc-id "$VPC_ID" --target-type ip \
    --health-check-path /health --health-check-interval-seconds 15 \
    --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 \
    --matcher HttpCode=200 --query 'TargetGroups[0].TargetGroupArn' --output text)
fi
# 30s instead of the 300s default: a redeploy should take a minute, not six.
aws elbv2 modify-target-group-attributes --region "$AWS_REGION" --target-group-arn "$TG_ARN" \
  --attributes Key=deregistration_delay.timeout_seconds,Value=30 >/dev/null

aws elbv2 describe-listeners --region "$AWS_REGION" --load-balancer-arn "$ALB_ARN" \
  --query 'Listeners[?Port==`80`]' --output text | grep -q . \
  || aws elbv2 create-listener --region "$AWS_REGION" --load-balancer-arn "$ALB_ARN" \
       --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn="$TG_ARN" >/dev/null

ALB_DNS=$(aws elbv2 describe-load-balancers --region "$AWS_REGION" \
  --load-balancer-arns "$ALB_ARN" --query 'LoadBalancers[0].DNSName' --output text)

say "ECS cluster"
aws ecs describe-clusters --region "$AWS_REGION" --clusters "$CLUSTER" \
  --query 'clusters[0].status' --output text 2>/dev/null | grep -q ACTIVE \
  || aws ecs create-cluster --region "$AWS_REGION" --cluster-name "$CLUSTER" >/dev/null
echo "ok: cluster $CLUSTER"

say "Provisioned. Append these to infra/aws/deploy/00-env.sh:"
cat <<EOF
export PLATFORM_SECRET_ARN=$PLATFORM_SECRET_ARN
export VPC_ID=$VPC_ID
export SUBNET_IDS="$SUBNET_IDS"
export ALB_SG=$ALB_SG
export TASK_SG=$TASK_SG
export TG_ARN=$TG_ARN
export ALB_DNS=$ALB_DNS
export PLATFORM_URL=http://$ALB_DNS
EOF