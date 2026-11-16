#!/usr/bin/env bash
# Builds every image and pushes it to its ECR repository.
# Run after the ECR repositories exist (see README, "Deploy to AWS").
set -euo pipefail

REGION="${AWS_REGION:-us-west-2}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
REGISTRY="$(aws sts get-caller-identity --query Account --output text).dkr.ecr.${REGION}.amazonaws.com"

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

# push only the images named as arguments, or all of them
names=("$@")
[ ${#names[@]} -eq 0 ] && names=(product credit-card shopping-cart warehouse load-client)

for name in "${names[@]}"; do
  image="${REGISTRY}/online-store/${name}:latest"
  echo "=== ${name} → ${image}"
  docker build -t "$image" "${ROOT}/${name}"
  docker push "$image"
done
