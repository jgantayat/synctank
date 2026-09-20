#!/usr/bin/env bash
# Day 11 — re-authorise the ALB for wherever you are now. Run this from the venue wifi
# before the pitch; it is the single most likely cause of "it worked at home".
# Removes every previous CIDR so the allowlist never silently accumulates old networks.
set -euo pipefail
: "${AWS_REGION:?source 00-env.sh first}" "${ALB_SG:?}"

NEW_CIDR="$(curl -s https://checkip.amazonaws.com | tr -d '\n')/32"
echo "This machine's public address: $NEW_CIDR"

aws ec2 describe-security-groups --region "$AWS_REGION" --group-ids "$ALB_SG" \
  --query 'SecurityGroups[0].IpPermissions[?FromPort==`80`].IpRanges[].CidrIp' --output text \
| tr '\t' '\n' | grep -v '^$' | while read -r old; do
    [ "$old" = "$NEW_CIDR" ] && continue
    echo "revoking $old"
    aws ec2 revoke-security-group-ingress --region "$AWS_REGION" --group-id "$ALB_SG" \
      --protocol tcp --port 80 --cidr "$old" >/dev/null
  done

aws ec2 authorize-security-group-ingress --region "$AWS_REGION" --group-id "$ALB_SG" \
  --protocol tcp --port 80 --cidr "$NEW_CIDR" 2>/dev/null || true
echo "ok: $ALB_SG now allows $NEW_CIDR on port 80, and nothing else."