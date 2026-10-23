#!/bin/bash

set -e

# Set the base directory to the script's location
BASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "$(uname -m)" in
  x86_64)  GOSS_ARCH=amd64 ;;
  aarch64) GOSS_ARCH=arm64 ;;
  *)       echo "Error: unsupported host arch $(uname -m)"; exit 1 ;;
esac
export GOSS_PATH="$BASE_DIR/tests/goss-linux-$GOSS_ARCH"
export GOSS_OPTS="${GOSS_OPTS} --format documentation"
export GOSS_FILES_STRATEGY="cp"

DOCKER_IMAGE="$1"
REPORTS_DIR="$2"
DOCKER_TEST_IMAGE="web3signer_goss"

# Check if DOCKER_IMAGE is provided
if [ -z "$DOCKER_IMAGE" ]; then
  echo "Error: Docker image not specified."
  exit 1
fi

# Check if REPORTS_DIR is provided
if [ -z "$REPORTS_DIR" ]; then
  echo "Error: Reports directory not specified."
  exit 1
fi

# Create the reports directory if it doesn't exist
mkdir -p "$REPORTS_DIR"

# Create test Docker image that includes the test key file and password files
TEST_CONTAINER_ID=$(docker create "$DOCKER_IMAGE")
docker commit "$TEST_CONTAINER_ID" "$DOCKER_TEST_IMAGE"
docker rm "$TEST_CONTAINER_ID" > /dev/null

# Initialize the exit code
i=0

# Test for normal startup with ports opened
GOSS_FILES_PATH="$BASE_DIR/tests/01" \
bash "$BASE_DIR/tests/dgoss" \
run --sysctl net.ipv6.conf.all.disable_ipv6=1 "$DOCKER_TEST_IMAGE" \
--http-listen-host=0.0.0.0 \
eth2 \
--slashing-protection-enabled=false \
> "$REPORTS_DIR/goss-report.txt" || i=$((i + 1))

# Remove the test Docker image
docker image rm "$DOCKER_TEST_IMAGE"

echo "test.sh Exit code: $i"
exit $i