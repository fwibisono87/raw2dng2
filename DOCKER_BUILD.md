# Docker Build Guide for Raw2DNG

This guide explains how to build the Raw2DNG APK using Docker, without installing Android Studio or any Android development tools locally.

## Prerequisites

Only **Docker** and **Docker Compose** are required. No Android SDK, NDK, or development tools needed on your host machine!

### Install Docker

#### Linux (Ubuntu/Debian)
```bash
# Install Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Add your user to docker group (to run without sudo)
sudo usermod -aG docker $USER

# Log out and back in for group changes to take effect

# Install Docker Compose
sudo apt-get update
sudo apt-get install docker-compose-plugin
```

#### macOS
```bash
# Download and install Docker Desktop
# https://www.docker.com/products/docker-desktop

# Or using Homebrew
brew install --cask docker
```

#### Windows
```powershell
# Download and install Docker Desktop
# https://www.docker.com/products/docker-desktop
```

### Verify Installation

```bash
docker --version
# Should show: Docker version 20.x.x or later

docker compose version
# Should show: Docker Compose version 2.x.x or later
```

## Quick Start (3 Steps)

### Step 1: Get Adobe DNG SDK (Required for Real Conversion)

```bash
# 1. Download Adobe DNG SDK from:
#    https://helpx.adobe.com/camera-raw/digital-negative.html

# 2. Extract the downloaded ZIP file

# 3. Copy SDK files to project
cd /path/to/raw2dng2
cp /path/to/dng_sdk/source/*.cpp app/src/main/cpp/dng_sdk/
cp /path/to/dng_sdk/source/*.h app/src/main/cpp/dng_sdk/

# 4. Verify
ls app/src/main/cpp/dng_sdk/dng_*.cpp | wc -l
# Should show 50+ files
```

**Note**: You can skip this step to test the build process, but the app will only create placeholder files instead of real DNG conversions.

### Step 2: Build the APK

```bash
# Using the convenience script (recommended)
./docker-build.sh

# Or using Make
make build

# Or using Docker Compose directly
docker-compose run --rm android-builder
```

**First build takes 10-15 minutes** (downloading Android SDK, compiling DNG SDK). Subsequent builds are much faster (2-5 minutes).

### Step 3: Get Your APK

```bash
# Your APK is ready!
ls -lh output/

# You should see:
# app-debug.apk              - Ready to install
# app-release-unsigned.apk   - Unsigned release version
```

## Installation

### Install on Android Device

#### Method 1: ADB (Android Debug Bridge)

```bash
# Connect your Android device via USB
# Enable USB debugging on device:
#   Settings → About Phone → Tap "Build Number" 7 times
#   Settings → Developer Options → Enable "USB Debugging"

# Install the APK
adb install output/app-debug.apk

# Or using Make
make install
```

#### Method 2: Manual Transfer

```bash
# Copy APK to your device
# On Linux/macOS:
cp output/app-debug.apk ~/Downloads/

# Transfer via USB, email, or cloud storage
# On device: Open file manager → Find app-debug.apk → Tap to install
```

## Build Commands

### Using Make (Easiest)

```bash
make build      # Build APK
make clean      # Clean build artifacts
make install    # Install on connected device
make shell      # Open shell in Docker container (for debugging)
make setup-sdk  # Show Adobe DNG SDK setup instructions
make help       # Show all available commands
```

### Using Docker Compose Directly

```bash
# Build APK
docker-compose run --rm android-builder

# Build with custom command
docker-compose run --rm android-builder ./gradlew assembleDebug

# Open interactive shell
docker-compose run --rm android-builder /bin/bash

# Clean and rebuild
docker-compose run --rm android-builder ./gradlew clean assembleDebug

# Remove Docker image (to force rebuild)
docker-compose down --rmi all
```

### Using the Build Script

```bash
# Standard build
./docker-build.sh

# The script will:
# 1. Check if Docker is installed
# 2. Build Docker image if needed (first time only)
# 3. Run the build in a container
# 4. Copy APKs to output/ directory
```

## Build Process Details

### What Happens During Build

1. **Docker Image Creation** (first time only, 5-10 minutes)
   - Downloads Ubuntu 22.04 base image
   - Installs Java 17
   - Downloads Android SDK command-line tools
   - Installs Android SDK platform tools, build tools
   - Installs Android NDK (Native Development Kit)
   - Installs CMake for native builds

2. **APK Build Process** (10-15 minutes first time, 2-5 minutes subsequent)
   - Gradle downloads dependencies (first time only)
   - Compiles Adobe DNG SDK C++ code (if present)
   - Compiles JNI bridge code
   - Builds Kotlin/Java Android app code
   - Packages everything into APK
   - Signs APK with debug key
   - Copies APK to output/ directory

3. **Output**
   - `output/app-debug.apk` - Ready to install and test
   - `output/app-release-unsigned.apk` - Unsigned release (requires signing)

### Build Performance

| Build Type | First Time | Subsequent |
|------------|------------|------------|
| Docker Image | 5-10 min | 0 sec (cached) |
| APK Build | 10-15 min | 2-5 min |
| Clean Build | 10-15 min | 10-15 min |

**Tip**: Don't run `make clean` unless necessary. Incremental builds are much faster!

## Troubleshooting

### Docker Issues

**"Cannot connect to Docker daemon"**
```bash
# Start Docker service
sudo systemctl start docker

# Or on macOS/Windows, start Docker Desktop application
```

**"Permission denied" when running docker**
```bash
# Add your user to docker group
sudo usermod -aG docker $USER

# Log out and back in, then try again
```

**"Docker image build fails"**
```bash
# Check Docker logs
docker-compose build --no-cache

# If download fails, try again (sometimes network issues)
```

### Build Issues

**"Adobe DNG SDK not found" warning**
- This is OK for testing! The app will build but create placeholder files.
- For real conversion, download and install the Adobe DNG SDK (see Step 1)

**"Gradle build failed"**
```bash
# Try clean build
docker-compose run --rm android-builder ./gradlew clean build

# Check available disk space
df -h

# Increase Docker memory limit (Docker Desktop → Settings → Resources)
```

**Build is very slow**
```bash
# First build is always slow (15+ minutes)
# Subsequent builds should be 2-5 minutes

# To speed up:
# 1. Don't run 'clean' unless necessary
# 2. Increase Docker memory (Settings → Resources → 4GB+)
# 3. Use SSD for Docker storage
```

**"Out of memory" during build**
```bash
# Increase Gradle heap size
# Edit gradle.properties:
org.gradle.jvmargs=-Xmx4096m

# Increase Docker memory limit
# Docker Desktop → Settings → Resources → Memory → 6GB+
```

### APK Install Issues

**"App not installed" on device**
```bash
# Uninstall previous version first
adb uninstall com.raw2dng

# Try again
adb install output/app-debug.apk
```

**"Signature conflict"**
```bash
# Uninstall existing app
adb uninstall com.raw2dng

# Or on device: Settings → Apps → Raw2DNG → Uninstall
```

## Advanced Usage

### Custom Build Configuration

Edit `docker-compose.yml` to customize:

```yaml
environment:
  # Increase Gradle memory
  - GRADLE_OPTS=-Xmx4096m -Dorg.gradle.daemon=false

  # Enable build cache (faster rebuilds)
  - GRADLE_OPTS=-Dorg.gradle.caching=true
```

### Building Release APK (Signed)

```bash
# Generate keystore (one time)
keytool -genkey -v -keystore raw2dng.keystore \
  -alias raw2dng -keyalg RSA -keysize 2048 -validity 10000

# Create signing config (app/build.gradle)
# Then build
docker-compose run --rm android-builder ./gradlew assembleRelease
```

### Debugging Build Issues

```bash
# Open shell in container
docker-compose run --rm android-builder /bin/bash

# Inside container, you can:
./gradlew assembleDebug --stacktrace  # Detailed error output
./gradlew assembleDebug --info         # Verbose logging
./gradlew assembleDebug --debug        # Very verbose logging

# Check SDK installation
ls -la $ANDROID_HOME

# Check NDK
ls -la $ANDROID_NDK_HOME
```

### Cleaning Everything

```bash
# Clean build artifacts
make clean

# Remove Docker image and volumes
docker-compose down --rmi all --volumes

# This will force complete rebuild next time (slow!)
```

## Workflow Summary

### Development Workflow

```bash
# 1. Make code changes
vim app/src/main/java/com/raw2dng/MainActivity.kt

# 2. Rebuild (incremental build is fast)
make build

# 3. Install on device
make install

# 4. Test on device
```

### First-Time Setup Workflow

```bash
# 1. Clone repository
git clone <repo-url>
cd raw2dng2

# 2. Get Adobe DNG SDK (optional but recommended)
# Download from Adobe website
cp /path/to/dng_sdk/source/*.{cpp,h} app/src/main/cpp/dng_sdk/

# 3. Build APK
make build
# Wait 10-15 minutes for first build

# 4. Install on device
make install

# 5. Test conversion
# Open app → Select RAW files → Convert
```

## FAQ

**Q: Do I need Android Studio?**
A: No! Docker builds everything. You only need a text editor and Docker.

**Q: Can I build on Windows?**
A: Yes! Install Docker Desktop for Windows and use the same commands in PowerShell or WSL2.

**Q: How big is the Docker image?**
A: ~4-5 GB (includes Android SDK, NDK, and build tools)

**Q: Can I delete the Docker image after building?**
A: Yes, but you'll need to rebuild it next time (10-15 minutes). Better to keep it cached.

**Q: Why is first build so slow?**
A: Gradle downloads dependencies (~500MB) and compiles the entire DNG SDK (~50+ C++ files). This is cached for subsequent builds.

**Q: Can I build without the Adobe DNG SDK?**
A: Yes, the app will build successfully but will only create placeholder DNG files. For real conversion, you need the SDK.

**Q: Does this work on ARM Macs (M1/M2)?**
A: Yes! Docker handles the architecture automatically.

## See Also

- [README.md](README.md) - Complete project documentation
- [SETUP_INSTRUCTIONS.md](SETUP_INSTRUCTIONS.md) - Manual setup without Docker
- [Adobe DNG SDK](https://helpx.adobe.com/camera-raw/digital-negative.html) - Download SDK

## Support

Having issues? Check:
1. This troubleshooting section
2. [README.md](README.md) troubleshooting section
3. Docker logs: `docker-compose logs`
4. Build logs in the terminal output

---

**Happy Building!** 🚀
