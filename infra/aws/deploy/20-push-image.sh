#!/usr/bin/env bash
# Day 11 — build the Day 10 image for the ARCHITECTURE FARGATE RUNS and push it to ECR.
#
# --platform linux/amd64 is not optional on an Apple Silicon Mac. Without it you get an
# arm64 image, the task definition says X86_64, and ECS fails the task with
# "image Manifest does not contain descriptor matching platform" — a message that sounds
# like a registry problem and is a laptop problem.
set -euo pipefail
cd "$(dirname "$0")/../../.."
: "${AWS_REGION:?source 00-env.sh first}" "${AWS_ACCOUNT_ID:?}" "${IMAGE_TAG:?}"

REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
IMAGE="${REGISTRY}/${ECR_REPO}:${IMAGE_TAG}"

echo "=== docker login to $REGISTRY"
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY"

echo "=== build $IMAGE (tests run inside the build, per the Day 10 Dockerfile)"
docker build --platform linux/amd64 --provenance=false --sbom=false -t "$IMAGE" contract-platform

echo "=== push"
docker push "$IMAGE"

echo "=== verify the manifest is amd64"
aws ecr describe-images --region "$AWS_REGION" --repository-name "$ECR_REPO" \
  --image-ids imageTag="$IMAGE_TAG" \
  --query 'imageDetails[0].{tag:imageTags[0],pushed:imagePushedAt,mb:imageSizeInBytes}' --output table
docker image inspect "$IMAGE" --format 'local arch: {{.Architecture}}'