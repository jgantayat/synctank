#!/usr/bin/env bash
# Day 13 — Slide 4, optional beat: "and on mine, if I try to force it."
#
# Skips the model entirely (the Day 07 operator-override path) and hands the agent a proposal
# a person might plausibly want — a money field as BigDecimal — to show the guardrails are Java
# that runs on ANY input, not a prompt the model is asked to respect. Deterministic: it does
# not depend on what the model says, so it is also the refusal to use if the typed one
# ("remove the amount field ...") ever comes back as something other than a refusal.
#
# Writes one BLOCKED audit row. Writes nothing to GitHub — a blocked draft cannot be approved.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need curl jq

PLATFORM="$(platform_url)"
say "Forcing a proposal past the model — $PLATFORM"
cat <<'EOF'
  POST /agent/requests   override = OrderResponse.discount : BigDecimal
EOF

jq -n '{
  request: "Operator override: add a discount to the order response as BigDecimal",
  requester: "jay@synctank",
  override: {
    targetSchema: "OrderResponse", fieldName: "discount", javaType: "BigDecimal",
    description: "Discount applied to the order", rationale: "Forced by the operator, not the model",
    confidence: "HIGH", clarifyingQuestion: ""
  }
}' \
| curl -fsS --max-time 30 -X POST "$PLATFORM/agent/requests" \
    -H 'Content-Type: application/json' --data-binary @- \
| jq -r '"status:  \(.status)", "reason:  \(.blockedReason)", "", "guardrails that ran first:", (.guardrails[] | "  - \(.)")'
