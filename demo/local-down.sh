#!/usr/bin/env bash
# Day 13 — stop the local stack. Volumes are kept, so the next local-up.sh is fast and the
# local spec history and registry survive.
# shellcheck source=demo/lib.sh
source "$(dirname "$0")/lib.sh"
need docker
cd "$ROOT" || exit 1
docker compose --profile platform stop contract-platform
docker compose stop minio postgres localstack
docker compose --profile platform ps
