#!/bin/bash
# Day 10 (F2) — (re)create the platform's secret every time LocalStack reaches READY.
#
# Why: resource persistence is a LocalStack Pro feature. On the community image every restart
# starts with an empty Secrets Manager, and contract-platform (SECRETS_PROVIDER=aws) then refuses
# to boot — correctly, but inconveniently. This hook makes a restart a non-event.
#
# Contains no credential. Values come from a gitignored file mounted by docker-compose.yml:
#   host:      infra/localstack/secrets/secrets.local.json
#   container: /etc/synctank/secrets/secrets.local.json
#
# Never prints a value. `--output text --query Name` echoes only the secret's name.
set -euo pipefail

# awslocal needs *some* credentials and a region to sign with; LocalStack accepts any value.
# Defaults only — anything already set in the container environment wins.
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="${AWS_DEFAULT_REGION:-us-east-1}"

SECRET_ID="synctank/platform"
SECRET_FILE="/etc/synctank/secrets/secrets.local.json"

if [ ! -f "$SECRET_FILE" ]; then
  echo "[synctank] $SECRET_FILE not found — not seeding '$SECRET_ID'."
  echo "[synctank] Copy infra/localstack/secrets/secrets.example.json to secrets.local.json and fill it in."
  exit 0
fi

# Refuse malformed JSON here, with a readable message, rather than storing it and letting the
# platform fail later with "Secret ... is not valid JSON". LocalStack's image ships python3.
if ! python3 -m json.tool "$SECRET_FILE" > /dev/null 2>&1; then
  echo "[synctank] ERROR: $SECRET_FILE is not valid JSON — secret NOT created." >&2
  exit 1
fi

if awslocal secretsmanager describe-secret --secret-id "$SECRET_ID" > /dev/null 2>&1; then
  awslocal secretsmanager put-secret-value \
    --secret-id "$SECRET_ID" \
    --secret-string "file://$SECRET_FILE" > /dev/null
  echo "[synctank] Updated secret '$SECRET_ID' from $SECRET_FILE (values not printed)."
else
  NAME=$(awslocal secretsmanager create-secret \
    --name "$SECRET_ID" \
    --description "SyncTank contract-platform credentials (seeded by init hook)" \
    --secret-string "file://$SECRET_FILE" \
    --output text --query Name)
  echo "[synctank] Created secret '$NAME' from $SECRET_FILE (values not printed)."
fi