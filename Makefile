# Makefile for Raw2DNG Android App
# Provides convenient shortcuts for Docker-based builds

.PHONY: help build clean install shell setup-sdk

help:
	@echo "Raw2DNG Build Commands"
	@echo "======================"
	@echo ""
	@echo "  make build       - Build APK using Docker"
	@echo "  make clean       - Clean build artifacts"
	@echo "  make install     - Install debug APK on connected device"
	@echo "  make shell       - Open shell in Docker container"
	@echo "  make setup-sdk   - Show instructions for Adobe DNG SDK setup"
	@echo ""

build:
	@echo "Building APK with Docker..."
	./docker-build.sh

clean:
	@echo "Cleaning build artifacts..."
	rm -rf app/build/
	rm -rf build/
	rm -rf .gradle/
	rm -rf output/
	@echo "Clean complete!"

install:
	@if [ ! -f "output/app-debug.apk" ]; then \
		echo "Error: APK not found. Run 'make build' first."; \
		exit 1; \
	fi
	@echo "Installing APK on device..."
	adb install -r output/app-debug.apk

shell:
	@echo "Opening shell in Docker container..."
	docker-compose run --rm android-builder /bin/bash

setup-sdk:
	@echo ""
	@echo "Adobe DNG SDK Setup Instructions"
	@echo "=================================="
	@echo ""
	@echo "1. Download Adobe DNG SDK:"
	@echo "   https://helpx.adobe.com/camera-raw/digital-negative.html"
	@echo ""
	@echo "2. Extract the downloaded ZIP file"
	@echo ""
	@echo "3. Copy SDK files to project:"
	@echo "   cp /path/to/dng_sdk/source/*.cpp app/src/main/cpp/dng_sdk/"
	@echo "   cp /path/to/dng_sdk/source/*.h app/src/main/cpp/dng_sdk/"
	@echo ""
	@echo "4. Verify installation:"
	@echo "   ls -l app/src/main/cpp/dng_sdk/dng_*.cpp | wc -l"
	@echo "   (Should show 50+ files)"
	@echo ""
	@echo "5. Build the app:"
	@echo "   make build"
	@echo ""
