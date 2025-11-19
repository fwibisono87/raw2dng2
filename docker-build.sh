#!/bin/bash

# Wrapper script to build Raw2DNG APK using Docker
# This is the main entry point for building without local Android SDK

set -e

echo "========================================="
echo "Raw2DNG Docker Build"
echo "========================================="
echo ""

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    echo "Error: Docker is not installed!"
    echo "Please install Docker from: https://docs.docker.com/get-docker/"
    exit 1
fi

# Check if Docker Compose is installed
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null 2>&1; then
    echo "Error: Docker Compose is not installed!"
    echo "Please install Docker Compose from: https://docs.docker.com/compose/install/"
    exit 1
fi

# Determine docker-compose command (v1 vs v2)
if docker compose version &> /dev/null 2>&1; then
    DOCKER_COMPOSE="docker compose"
else
    DOCKER_COMPOSE="docker-compose"
fi

echo "Using: $DOCKER_COMPOSE"
echo ""

# Check if we need to build the Docker image
echo "Checking Docker image..."
if ! docker images | grep -q "raw2dng2-android-builder"; then
    echo "Building Docker image (this may take 10-15 minutes on first run)..."
    echo ""
    $DOCKER_COMPOSE build
    echo ""
    echo "✓ Docker image built successfully!"
    echo ""
else
    echo "✓ Docker image exists"
    echo ""
fi

# Run the build
echo "Starting APK build in Docker container..."
echo ""
echo "NOTE: First build may take 10-15 minutes as Gradle downloads dependencies"
echo "      and compiles the DNG SDK. Subsequent builds will be much faster."
echo ""

$DOCKER_COMPOSE run --rm android-builder

echo ""
echo "========================================="
echo "Docker Build Complete!"
echo "========================================="
echo ""
echo "Your APK is ready in the 'output/' directory"
echo ""
