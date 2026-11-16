#!/usr/bin/env bash
# Runs the load client inside AWS against the load balancer and prints its result.
# Usage: ./run-load-test.sh <product|checkout> <threads> [totalRequests]
set -euo pipefail
export AWS_REGION="${AWS_REGION:-us-west-2}" MSYS_NO_PATHCONV=1

MODE="${1:?mode: product or checkout}"
THREADS="${2:?threads}"
TOTAL="${3:-200000}"

TF="$(cd "$(dirname "$0")/terraform" && pwd)"
ALB_URL="$(terraform -chdir="$TF" output -raw alb_url)"
NETWORK="$(terraform -chdir="$TF" output -raw load_client_network)"
CLUSTER="$(terraform -chdir="$TF" output -raw cluster_name)"

OVERRIDES="{\"containerOverrides\":[{\"name\":\"load-client\",\"command\":[\"$MODE\",\"$ALB_URL\",\"$THREADS\",\"$TOTAL\"]}]}"
TASK_ARN="$(aws ecs run-task --cluster "$CLUSTER" --launch-type FARGATE \
  --task-definition online-store-load-client --network-configuration "$NETWORK" \
  --overrides "$OVERRIDES" --query 'tasks[0].taskArn' --output text)"
TASK_ID="${TASK_ARN##*/}"
echo "Started $MODE test: $THREADS threads, $TOTAL requests (task $TASK_ID)"

aws ecs wait tasks-running --cluster "$CLUSTER" --tasks "$TASK_ARN"
echo "Running since $(date +%H:%M:%S)…"
until [ "$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].lastStatus' --output text)" = "STOPPED" ]; do
  sleep 10
done

aws logs get-log-events --log-group-name /ecs/online-store \
  --log-stream-name "ecs/load-client/$TASK_ID" --query 'events[].message' --output text | tr '\t' '\n'
