#!/usr/bin/env bash
# Builds every image and pushes it to its ECR repository.
# Run after the ECR repositories exist (see README, "Deploy to AWS").
set -euo pipefail

REGION="${AWS_REGION:-us-west-2}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
REGISTRY="$(aws sts get-caller-identity --query Account --output text).dkr.ecr.${REGION}.amazonaws.com"

aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

for name in product credit-card shopping-cart warehouse; do
  image="${REGISTRY}/online-store/${name}:latest"
  echo "=== ${name} → ${image}"
  docker build -t "$image" "${ROOT}/${name}"
  docker push "$image"
done
