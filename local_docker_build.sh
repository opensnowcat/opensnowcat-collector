#!/bin/bash

# Script to reproduce the GitHub Actions deploy_docker job locally
# This builds Docker images for all platforms without pushing

set -e

PLATFORMS=("kinesis" "sqs" "pubsub" "kafka" "nsq" "stdout" "rabbitmq")

for platform in "${PLATFORMS[@]}"; do
  suffix=""
  if [ "$platform" = "rabbitmq" ]; then
    suffix="-experimental"
  fi

  image_name="opensnowcat/opensnowcat-collector-$platform$suffix"

  echo "Staging Docker build for $platform..."
  sbt "project $platform" docker:stage

  echo "Building Docker image for $platform..."
  docker build -t "$image_name:latest-noble" "$platform/target/docker/stage"

  echo "Staging distroless Docker build for $platform..."
  sbt "project ${platform}Distroless" docker:stage

  echo "Building distroless Docker image for $platform..."
  docker build -t "$image_name:latest-distroless" "distroless/$platform/target/docker/stage"

  echo "Completed for $platform"
done

echo "All Docker images built locally."
