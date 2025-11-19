# Quick Start Guide - Raw2DNG

Get your APK in 5 minutes using Docker!

## One-Command Build

```bash
# Clone repo
git clone <your-repo-url>
cd raw2dng2

# Build APK with Docker
make build

# Done! APK is in output/app-debug.apk
```

## What You Need

- ✅ Docker installed ([Get Docker](https://docs.docker.com/get-docker/))
- ✅ 5-10 GB free disk space
- ✅ Internet connection (for first build)

## Step-by-Step

### 1. Install Docker

**Already have Docker?** Skip to step 2.

- **macOS/Windows**: Download [Docker Desktop](https://www.docker.com/products/docker-desktop)
- **Linux**:
  ```bash
  curl -fsSL https://get.docker.com -o get-docker.sh
  sudo sh get-docker.sh
  ```

Verify:
```bash
docker --version
# Should show: Docker version 20.x or later
```

### 2. Get the Code

```bash
git clone <your-repo-url>
cd raw2dng2
```

### 3. (Optional) Add Adobe DNG SDK

**Want real RAW conversion?** Add the Adobe DNG SDK:

```bash
# 1. Download from: https://helpx.adobe.com/camera-raw/digital-negative.html
# 2. Extract the ZIP
# 3. Copy files:
cp /path/to/dng_sdk/source/*.cpp app/src/main/cpp/dng_sdk/
cp /path/to/dng_sdk/source/*.h app/src/main/cpp/dng_sdk/

# 4. Verify:
ls app/src/main/cpp/dng_sdk/dng_*.cpp | wc -l
# Should show 50+ files
```

**Skip this for now?** No problem! The app will build and run, but create placeholder files instead of real DNG conversions.

### 4. Build the APK

```bash
# Easy way (with Make):
make build

# Or using the script:
./docker-build.sh

# Or using Docker Compose:
docker-compose run --rm android-builder
```

**First build takes 10-15 minutes** (downloads Android SDK, compiles native code).
**Subsequent builds: 2-5 minutes** (much faster!)

☕ Grab a coffee while it builds...

### 5. Get Your APK

```bash
# Your APK is ready!
ls -lh output/

# You should see:
# app-debug.apk (ready to install!)
```

### 6. Install on Device

**Option A: USB Cable (ADB)**
```bash
# Connect device via USB
# Enable USB debugging on device
adb install output/app-debug.apk
```

**Option B: Manual Transfer**
```bash
# Copy APK to your phone
# Open file manager on phone
# Tap app-debug.apk to install
```

### 7. Test It!

1. Open "Raw2DNG" app on your device
2. Tap "SELECT RAW FILES"
3. Choose your RAW files (CR2, ARW, NEF, etc.)
4. Tap "CONVERT TO DNG"
5. Watch the conversion progress!

Converted files are in:
```
/Android/data/com.raw2dng/files/Documents/Raw2DNG/
```

## Troubleshooting

**"Docker not found"**
```bash
# Install Docker first
# See: https://docs.docker.com/get-docker/
```

**Build is slow**
- First build is always 10-15 minutes (downloads ~2GB)
- Subsequent builds are faster (2-5 minutes)
- Be patient! ☕

**"Out of memory" error**
```bash
# Increase Docker memory limit
# Docker Desktop → Settings → Resources → Memory → 4GB+
```

**Can't install APK on device**
```bash
# Enable "Unknown sources" / "Install from unknown sources"
# Settings → Security → Unknown sources → Enable
```

## Next Steps

- Read full docs: [README.md](README.md)
- Docker details: [DOCKER_BUILD.md](DOCKER_BUILD.md)
- Manual setup: [SETUP_INSTRUCTIONS.md](SETUP_INSTRUCTIONS.md)

## Commands Cheat Sheet

```bash
make build       # Build APK
make clean       # Clean build artifacts
make install     # Install on connected device
make shell       # Open shell in Docker container
make help        # Show all commands
```

## Support

Having issues?
1. Check [DOCKER_BUILD.md](DOCKER_BUILD.md) troubleshooting
2. Check [README.md](README.md) troubleshooting
3. Make sure Docker is running
4. Try: `docker-compose down && docker-compose build --no-cache`

---

**That's it! Happy converting!** 🎉
