# Android Automotive OS (AAOS) Emulator Test Configuration Guide for DriveCast

This guide details how to set up and run the official Android Automotive OS (AAOS) emulator to validate Media3 ExoPlayer video rendering, hardware decoders (`MediaCodecVideoRenderer`), and car surface projection (`CarSurfaceSink`) without needing physical vehicle hardware.

---

## 1. Prerequisites in Android Studio / SDK Manager
1. Open **Android Studio** -> **SDK Manager** -> **SDK Platforms**. Ensure **Android 34 (API 34)** or **Android 35** is installed.
2. Go to **SDK Tools** and ensure:
   - **Android Emulator**
   - **Android SDK Platform-Tools**
   - **Intel x86 Emulator Accelerator (HAXM)** or **Google APIs ARM64 v8a System Image** (depending on host architecture) are installed.

---

## 2. Creating an Automotive Virtual Device (AVD)
1. Open **Device Manager** in Android Studio (`Tools` -> `Device Manager`).
2. Click **Create Device**.
3. Select the **Automotive** category in the left sidebar.
4. Choose a reference hardware profile (e.g., **Automotive 1024p landscape** or **Automotive portrait**). Click **Next**.
5. Select a system image:
   - Recommended: **Automotive with Play Store** (API 34 or API 35) or **Automotive Playback System Image**.
6. Name your AVD (e.g., `DriveCast_AAOS_API34`) and click **Finish**.

---

## 3. Configuring Car Hardware Properties & Speed Telemetry
To test the parked safety gate (`SafetyGateManager`) and vehicle speed sensor listeners (`CarHardwareManager`):
1. Locate your AVD in Device Manager, click the **three dots (...)**, and select **View Details** or edit `config.ini`.
2. Ensure car hardware features are enabled:
   - `hw.audio=yes`
   - `hw.lcd.density=240`
3. Launch the emulator. Once booted into the AAOS home screen, open the **Car Rotary / Car Controller** or **Extended Controls** panel (`...` button on emulator toolbar -> **Car Data** / **Sensor**).
4. In the **Car Data** tab:
   - Set **Vehicle Speed (km/h)** to `0` (Parked) to verify that video playback starts and surfaces un-blank.
   - Set **Vehicle Speed (km/h)** above `3.0` (e.g., `25 km/h`) to verify that the parked safety gate locks video rendering and switches to **Audio-Only Drive Mode** (or blanks video safely).

---

## 4. Validating Video Rendering & Diagnostics
1. Build and run DriveCast on the AAOS emulator:
   ```bash
   gradle :app:installDebug
   ```
2. Open the **DriveCast** app from the AAOS app launcher.
3. Open the **Car Link & Diagnostics** tab inside the app to inspect:
   - **MediaCodecVideoRenderer Diagnostics**: Verify that hardware acceleration is active and `Big Buck Bunny` or `Elephant's Dream` pre-seeded demo videos successfully initialize hardware decoders without software fallbacks.
   - **Android Auto / AAOS Diagnostics Card**: Confirms service binding (`VideoDeckCarAppService`), surface dimensions, and API levels.
   - **Parked Safety Gate**: Real-time speed telemetry and gate status.

---

## 5. Troubleshooting Common AAOS Emulator Issues
- **Black screen on car surface**: Ensure the vehicle speed is set to `0` in Extended Controls -> Car Data so the parked safety gate is verified and un-blanked.
- **Provider warnings (`androidx.car.app.connection`)**: Harmless non-fatal system logs when host projection services are initializing; fully covered by manifest package and provider queries.
