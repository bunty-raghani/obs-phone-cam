# OBS PhoneCam — Phone to OBS Studio as Webcam

> **This project is built on top of [Phone-to-OBS-Studio-as-Webcam](https://github.com/FurkanGozukara/Phone-to-OBS-Studio-as-Webcam) by [Furkan Gözükara](https://github.com/FurkanGozukara).**
> The original Android app, USB/ADB transport, and Python MPEG-TS relay are his design and work; all credit for the original project belongs to the original author and repository, and this derivative would not exist without it.
> This repository is an enhanced, cross-platform fork: it adds full macOS support, live front/rear camera swapping while streaming, and advanced capture settings in the phone app (quality presets, custom bitrate, rate control, processing mode, stabilization). See [What this fork adds](#what-this-fork-adds).

Use an Android phone as a camera source in OBS Studio over a USB cable at the resolutions and frame rates the phone's camera actually exposes (for example 720p, 1080p, or 4K at 30 or 60 FPS, on hardware that supports them).

The Android app captures with Camera2 and uses the phone's hardware H.264 encoder. A small Python relay receives the encoded frames through Android Debug Bridge (ADB), wraps them in timestamped MPEG-TS, and sends them directly to OBS on localhost. There is no Wi-Fi transport, cloud service, account, PC-side video re-encoding, or subscription.

After streaming starts, the phone can be locked and its display remains off. A foreground camera service detaches the preview and continues the encoder/USB pipeline, reducing display power and heat.

> [!IMPORTANT]
> This project creates an **OBS Media Source**. It does not install a system-wide virtual-camera driver for Zoom, Teams, or browser applications. You can use OBS Virtual Camera afterward if another application needs the composed OBS output.

## What this fork adds

Relative to the original [Phone-to-OBS-Studio-as-Webcam](https://github.com/FurkanGozukara/Phone-to-OBS-Studio-as-Webcam) project:

- **macOS support in every script** — `setup.sh`, `run_server.sh` / `run_server.command`, `install_and_run.sh` / `install_and_run.command`, and `build.sh` mirror the Windows scripts; both platform script sets live under `scripts/`, so installation, daily launch, and source builds work identically on macOS and Windows. The relay itself (`relay/server.py`) is the same platform-independent Python file on both.
- **Live camera swap** — a floating **⇄** button in the app (and a **Switch camera** notification action) switches between the front and rear lenses *without stopping the stream*. The USB link, wake lock, and foreground notification stay up; OBS keeps the same Media Source and resumes on the next keyframe. Your selected resolution/FPS carry over to the other lens whenever it supports them, with a smart fallback to the best available mode otherwise.
- **Advanced capture settings in the phone app** — quality presets with automatic bitrate (`Basic`, `Balanced`, `High (recommended)`, `Maximum`) plus a fully **Custom** bitrate, and an **Advanced** panel with **Rate control** (CBR/VBR, as reported by the encoder), **Processing** (`High quality` / `Fastest`), and **Stabilization** (shown when the lens supports it).
- **Device-agnostic name and package** — the project, launcher label, and APK are branded **OBS PhoneCam** (`OBSPhoneCam.apk`, package `com.local.obsphonecam`). Nothing is POCO-specific; the app lists exactly what any Android 8.0+ phone exposes.
- **Compatibility with the original app** — the relay also accepts the original `POCOCAM/*` handshake banner, so phones still running the original PocoCam app keep working with this server.

## Data path

```text
Android Camera2
      │ camera surface
      ▼
Hardware H.264 encoder (MediaCodec, selectable mode, no B-frames)
      │ framed H.264 over localhost TCP
      ▼
ADB reverse tunnel over USB
      │
      ▼
Python relay (no decode/re-encode)
      ├── MPEG-TS over UDP  ──► OBS Media Source (lowest latency)
      └── MPEG-TS over HTTP ──► OBS Media Source (fallback)
```

## Verified configurations

The **original release** was built and tested end to end by the original author with:

| Component | Verified configuration |
|---|---|
| Phone | POCO F7 (`25053PC47G`), Android 16 |
| PC | Windows, Python 3.10 |
| OBS | OBS Studio 31.1.2 |
| Video choices | 720p, 1080p, or 4K; 30 or 60 FPS; H.264 High profile |
| Modes sustained on the POCO F7 | 720p30, 720p60, 1080p30, 1080p60, and 4K30 |
| POCO F7 Camera2 limitation | 4K60 is not exposed to third-party Camera2 apps; the app reports it as unsupported rather than silently substituting a mode |
| Measured phone-to-PC latency | Approximately 91 ms at 720p60; higher modes measured approximately 104–195 ms before OBS decoding |
| Screen-off cadence test | Display asleep, 600/600 decoded 720p60 frames in 9.999 seconds |
| High-resolution screen-off test | 4K30 remained live and fully decoded with the display asleep |
| Screen-off thermal state | Android thermal status 0 during the test |
| Screen-off phone app usage | Approximately 24% of one CPU core and 53 MB PSS at 720p60 |
| Python relay usage | Approximately 24 MB RAM and 1–3% of one CPU core during the test |

The **OBS PhoneCam enhancements** (rename, macOS scripts, camera swap, quality/advanced settings) were built and tested on:

| Component | Verified configuration |
|---|---|
| Phone | Realme 2 Pro (`RMX1801`), Android 10 |
| PC | macOS, Python 3.14 |
| Camera swap | Idle preselection and live in-stream swap verified; mode carry-over and fallback verified |
| Relay handshake | `PHONECAM/2` (this app) and legacy `POCOCAM/2` (original app / earlier builds) both accepted |

Measurements are from the listed devices and are not guarantees for every phone, camera lens, USB controller, or OBS configuration. The app lists what your phone's Camera2 driver really supports — nothing is hard-coded to a brand.

## Files you need

- `dist/OBSPhoneCam.apk` — prebuilt Android application (works on any Android 8.0+ phone)
- `relay/server.py` — USB receiver and OBS relay; Python standard library only

Windows launchers (`scripts\windows\`):

- `install_and_run.bat` — first-time installation and server launcher
- `run_server.bat` — normal launcher after the app is installed
- `setup.ps1` — downloads Google's Android Platform-Tools when ADB is not already installed
- `build.ps1` — optional local APK build bootstrap

macOS launchers (`scripts/macos/`):

- `install_and_run.command` — double-clickable first-time installation and server launcher
- `run_server.command` — double-clickable normal launcher
- `install_and_run.sh` / `run_server.sh` — Terminal equivalents of the two `.command` files
- `setup.sh` — downloads Google's Android Platform-Tools when ADB is not already installed
- `build.sh` — optional local APK build bootstrap

Prebuilt APK (`dist/OBSPhoneCam.apk`) SHA-256:

```text
84B239138806A5A25528763D227ECE85C767B70D432C43636080B20483A73164
```

## Requirements

- Windows 10/11, or macOS (Apple Silicon or Intel)
- Python 3.10 or newer
  - Windows: available as `python` in `PATH`
  - macOS: available as `python3` in `PATH`. The Python 3.9 that ships with Apple's Command Line Tools is too old; install a current Python from [python.org](https://www.python.org/downloads/) or with `brew install python`
- [OBS Studio](https://obsproject.com/download)
- An Android phone with Android 8.0/API 26 or newer — any brand
- A USB data cable, not a charge-only cable
- Developer options and USB debugging enabled on the phone

> [!NOTE]
> iOS is not supported. The transport uses Android Debug Bridge (ADB) over USB, which exists only on Android.

The first launcher downloads the current [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) directly from Google into `.tools/platform-tools`. Nothing is installed system-wide.

## First-time setup using the prebuilt APK

### 1. Download the repository

Use **Code → Download ZIP** on this repository's GitHub page and extract it, or clone it with the URL shown there:

```powershell
git clone <repository-url>
cd <repository-folder>
```

Keep the repository folder intact — the launchers expect the layout described in [Repository layout](#repository-layout).

> [!NOTE]
> On macOS, if a launcher reports `permission denied` when you run it from Terminal, make the scripts executable once with `chmod +x *.sh *.command` in the project directory.

### 2. Enable USB debugging

On the Android phone:

1. Open **Settings → About phone**.
2. Tap the build/OS version repeatedly until Developer options are enabled.
3. Open **Developer options** and enable **USB debugging**.
4. Connect the phone with a USB data cable.
5. Select **File transfer / Android Auto** USB mode if the computer does not detect it.
6. Keep the phone unlocked and accept the **Allow USB debugging** prompt. Enabling “Always allow from this computer” avoids repeating the prompt.

The bootloader does not need to be unlocked, and the phone does not need root access.

### 3. Install and start the relay

On **Windows**, double-click:

```text
scripts\windows\install_and_run.bat
```

On **macOS**, double-click:

```text
scripts/macos/install_and_run.command
```

or run it from Terminal:

```bash
./scripts/macos/install_and_run.sh
```

On its first run it will:

1. Download Android Platform-Tools from Google if ADB is unavailable.
2. Detect exactly one authorized USB phone.
3. Install or update `OBSPhoneCam.apk`.
4. Create the USB reverse tunnel.
5. Start the local OBS relay.

If the console says the phone is unauthorized, unlock the phone, accept its USB-debugging prompt, and run the launcher again.

> [!NOTE]
> **Migrating from the original project or an earlier PocoCam build?** OBS PhoneCam installs as a separate app (`com.local.obsphonecam`), so it will not overwrite the old app; your phone may briefly show both. The relay accepts both the original and new handshake banners, so either app works with this server. Uninstall the old app from Android Settings once you have switched. The old package is `com.local.pococam`.

### 4. Start the Android app

Open **OBS PhoneCam**, hold or mount the phone in landscape, select a resolution and frame rate, and tap **Start**. The phone and server console will display measured resolution, FPS, bitrate, and capture-to-PC delay.

Wait until the app displays `LIVE over USB`. You may then press the power button and leave the phone locked; streaming continues with the display off.

## Choose resolution and frame rate

The app presents **Camera**, **Aspect ratio**, **Resolution**, and **Frame rate** selectors. Only combinations your phone's camera driver truly reports are listed:

- **Camera** — every lens the phone lets apps stream from (`Rear camera`, `Front camera`). Sensors the system reserves for itself are left out so that every listed camera actually works.
- **Aspect ratio** — the shapes the selected lens really offers, for example `16:9`, `4:3`, or `18:9`.
- **Resolution** — friendly names such as `1080p (Full HD) · 1920×1080` or `720p (HD) · 1280×720`. When a lens offers a mode larger than 1080p, it appears as `Maximum · WIDTH×HEIGHT`.
- **Frame rate** — 30, 60, 90, 120, or 240 fps, limited to the values the selected mode supports. Low anonymous rates such as 15 or 20 fps are never offered.

The default encoder bitrate automatically follows the selected pair:

| Selection | H.264 CBR target | Practical use |
|---|---:|---|
| 720p30 | 4 Mb/s | Lowest bandwidth and light load |
| 720p60 | 6 Mb/s | Default and lowest measured real-time delay |
| 1080p30 | 8 Mb/s | More detail at moderate load |
| 1080p60 | 12 Mb/s | Detailed smooth motion |
| 4K30 | 24 Mb/s | Maximum detail on the originally tested phone |
| 4K60 | 40 Mb/s | Available only when the selected lens exposes 4K60 through Camera2 |

The app requests the selected combination exactly. If the chosen lens does not expose it through Android Camera2, the app stops cleanly and displays an unsupported-mode message so the user can choose another combination. Higher resolutions require more phone encoder work, USB bandwidth, OBS decoder work, and usually more latency; turning the screen off still removes display and preview rendering.

To change modes, tap **Stop**, choose the new resolution/FPS pair, and tap **Start** again. The OBS Media Source URL does not change.

### Quality presets and advanced settings

The **QUALITY** section picks the automatic bitrate:

- **Basic / Balanced / High (recommended) / Maximum** — the app computes the encoder bitrate from the selected resolution and frame rate for that preset (shown live under the selector, e.g. `Auto: ~8.0 Mb/s at 30 fps`).
- **Custom…** — reveals a bitrate slider (up to the encoder's own reported maximum) and uses exactly the bitrate you choose, e.g. `12.0 Mb/s`.

The **Advanced ▸** panel exposes the encoder and camera options the hardware reports:

- **Rate control** — `CBR (constant bitrate)`, `VBR (variable bitrate)`, or `Default`, depending on what the phone's encoder advertises.
- **Processing** — `High quality` (default) or `Fastest`, for the encoder's operating mode.
- **Stabilization** — `Off` / `On`; the row is hidden on lenses that do not support video stabilization.

### Switch cameras while streaming

The floating **⇄** button beside the start/stop button switches between the front and rear lenses. While a stream is live, the app restarts the camera pipeline on the other lens without dropping the USB link, so OBS keeps the same Media Source and resumes on the next keyframe (the encoder is rebuilt for the new mode and the stream carries the new SPS/PPS in band). The last selected resolution and frame rate are kept whenever the other lens exposes them; otherwise the app falls back to the largest mode that supports a suitable frame rate (the same aspect ratio first), then the lens's largest available mode, and recomputes the bitrate from the selected quality preset. A custom bitrate is always kept. While idle, the button simply preselects the other lens for the next start. The notification also offers **Switch camera**, so the lens can be changed while the phone is locked.

### What appears on tested phones

The app lists exactly what the phone's camera driver exposes to apps. Two verified examples:

**POCO F7 (original author's test device):** 720p30, 720p60, 1080p30, 1080p60, and 4K30 sustained. 4K60 is not exposed to third-party Camera2 apps.

**Realme 2 Pro (RMX1801, Android 10):**

- **Two cameras**: `Rear camera` and `Front camera`. The back's second sensor (2 MP depth helper for portrait photos) is reserved by the system: it refuses to open for any third-party app and cannot produce a video stream, so it is not listed. The sidebar notes `extra rear sensor is system-only` when this happens.
- **No 4K**: the driver's largest mode for apps is `Maximum · 2592×1458` (16:9, larger than 1080p). The stock realme camera's 4K recording uses a private system path that the standard Android camera interface does not expose to apps.
- **30 fps maximum**: the driver's frame-rate table lists only rates up to 30 fps at every resolution, so the app offers `30 fps` only.
- **Front camera 1080p** is `1440×1080` in the `4:3` group — the front camera has no 1920×1080 mode for apps.

These limits come from the phone's firmware, not from the app: OBS PhoneCam hides any mode the driver would reject instead of offering options that fail to start. Any Android 8.0+ phone will show its own list of real modes.

## Stream with the phone screen off

OBS PhoneCam 1.5 runs the camera and encoder in an Android foreground service:

1. Start the stream while the OBS PhoneCam Activity is visible. Modern Android requires camera foreground services to be launched from a visible app.
2. Confirm `LIVE over USB` and a stable frame rate.
3. Press the phone's power button once to lock it.
4. Leave the USB cable connected. OBS and the Python console should continue receiving video.
5. Unlock/reopen the app whenever you need the live preview or Stop button. The notification also provides **Switch camera** and **Stop** actions.

The service removes the display preview surface while the Activity is paused and retains only the hardware encoder surface. It also holds a partial wake lock for the duration of the stream so the USB/network thread remains reliable; the wake lock is released immediately when streaming stops.

On HyperOS/MIUI/Oppo-family devices, if the service is terminated during a long locked session, set **Settings → Apps → OBS PhoneCam → Battery saver → No restrictions**. Do not enable unrestricted battery use unless the phone actually stops the foreground service.

## Add OBS PhoneCam to OBS Studio

In the desired OBS scene:

1. Click **Sources → + → Media Source**.
2. Give it a name such as `OBS PhoneCam`.
3. Clear **Local File**.
4. Use these properties:

| OBS property | Value |
|---|---|
| Input | `udp://127.0.0.1:5600` |
| Input Format | `mpegts` |
| Network Buffering | **`0 MB`** |
| Use hardware decoding when available | Enabled |
| Restart playback when source becomes active | Disabled |
| Close file when inactive | Disabled |

**Network Buffering must be 0 MB for the lowest latency.** OBS permits 0–16 MB; a large value can add obvious delay to this already reliable local USB connection.

Set **Settings → Video → Common FPS Value** to match the selected phone rate (`30` or `60`). Choose the canvas and recording output resolution appropriate for your production; the Media Source automatically receives the phone's selected resolution.

The source can be resized, cropped, and placed like any other OBS source. For the front camera, use **Transform → Flip Horizontal** if you want a mirrored image. Use **Transform → Rotate 180 degrees** if the physical mount is upside down. When you use the in-app **⇄** swap, OBS keeps this same Media Source — no changes needed in OBS.

### HTTP fallback

If UDP cannot be opened in a particular OBS installation, use:

```text
http://127.0.0.1:8766/stream.ts
```

Keep the input format as `mpegts`. HTTP is a reliable compatibility fallback, but UDP normally has less buffering and is preferred for real-time use.

## External microphone and screen-recording synchronization

Phone audio is deliberately not captured. This reduces phone/USB work and prevents a duplicate microphone track. The APK does not request Android microphone permission.

### Add the microphone

1. Add **Audio Input Capture** in OBS and select the external microphone.
2. Use 48 kHz in **OBS Settings → Audio** and in your operating system's microphone properties when the device supports it.
3. Open **Edit → Advanced Audio Properties**.
4. Enter a positive **Sync Offset** for the microphone. Start with the server console's current capture-to-PC value (also `capture_to_pc_ms` in the status endpoint)—approximately `+90 to +200 ms` across the tested modes.
5. Record a visible hand clap. If the sound occurs before the hands meet, increase the microphone offset. If it occurs afterward, decrease it.

The console measurement ends at the Python relay. OBS decoding and a particular microphone driver can add a small additional fixed difference, so the clap recording is the authoritative final calibration.

### Optional exact screen-to-camera alignment

Display Capture is local and appears earlier than the phone camera. If an on-screen event must align exactly with a physical action visible on the phone, add the calibrated delay to **Display Capture → Filters → Render Delay**.

Skip this filter for an ordinary webcam overlay when exact cross-source visual timing is unnecessary; buffering screen frames consumes additional GPU memory.

## Normal daily use

After the APK is installed:

1. Connect and unlock the phone.
2. Double-click `scripts\windows\run_server.bat` on Windows, or `scripts/macos/run_server.command` on macOS (`./scripts/macos/run_server.sh` in Terminal).
3. Open OBS PhoneCam, select the video mode, and tap **Start**.
4. After `LIVE over USB` appears, lock the phone to turn its display off.
5. Open OBS or show the existing `OBS PhoneCam` Media Source.

Only run one copy of the server. The final server reserves its localhost port exclusively and reports a clear error if another instance is already active.

To stop, tap **Stop** in the app and close the server console with `Ctrl+C`.

## Low-latency checklist

- Use the UDP source, not the HTTP fallback.
- Set OBS **Network Buffering to 0 MB**.
- Enable OBS hardware decoding.
- Use 720p60 for the lowest measured camera-to-PC delay on the tested phones.
- Set OBS output FPS to match the selected 30 or 60 FPS phone mode.
- Keep the phone connected directly to a stable USB port when possible.
- Avoid running a second OBS PhoneCam server.
- Lock the phone after the stream is live to eliminate display power and preview rendering.
- Calibrate microphone offset only after the video source is using 0 MB buffering.
- Do not add a Render Delay filter unless exact screen/camera alignment is required.

The phone encoder requests Android's optional one-frame encoder-latency mode, disables B-frames, and keeps only a bounded frame queue. The server never decodes or re-encodes the video.

## Live status

While the server is running, open:

```text
http://127.0.0.1:8766/status.json
```

Example:

```json
{
  "phone_connected": true,
  "width": 1280,
  "height": 720,
  "requested_fps": 60,
  "measured_fps": 60.0,
  "megabits_per_second": 5.4,
  "capture_to_pc_ms": 100,
  "udp_obs_url": "udp://127.0.0.1:5600",
  "http_obs_url": "http://127.0.0.1:8766/stream.ts"
}
```

After an in-app camera swap while streaming, `width`, `height`, and `requested_fps` update to the new lens mode automatically (the app sends an in-band mode notice, and the console prints `Camera switched: WIDTHxHEIGHT @ FPS`).

## Troubleshooting

### `No USB-debugging phone found`

- Use a known USB data cable.
- Unlock the phone.
- Select File transfer/Android Auto mode.
- Confirm USB debugging is enabled.
- Try another direct USB port.
- Run `.\tools\platform-tools\adb.exe devices -l` on Windows or `./.tools/platform-tools/adb devices -l` on macOS to check detection (`adb devices -l` if ADB is already in your `PATH`).

### `Phone is unauthorized`

Accept the authorization prompt on the unlocked phone. If no prompt appears, revoke USB-debugging authorizations in Developer options, reconnect the cable, and authorize the PC again.

### OBS is blank or says it cannot find stream information

1. Confirm the phone displays `LIVE over USB`.
2. Confirm the server displays `Phone connected`.
3. Verify the UDP URL and `mpegts` input format.
4. Wait up to one second for the next H.264 keyframe.
5. Reopen the Media Source properties or toggle the source visibility.

Start the server and phone stream before creating/restarting the OBS Media Source when possible.

### Video has a large visible delay

- Verify **Network Buffering is 0 MB**, not the OBS maximum of 16 MB.
- Verify the source uses UDP.
- Remove unnecessary video/render-delay filters.
- Keep hardware decoding enabled unless a specific GPU driver behaves incorrectly.
- Check `capture_to_pc_ms` in the console/status endpoint. This isolates phone/USB latency from OBS latency.

### FPS is below the selected value

Confirm that the intended frame rate is selected in the app. The app requests the selected resolution/FPS pair exactly, but Camera2 capabilities differ by phone and lens. If the pair is unavailable, the app reports it instead of pretending another mode is the requested one. Try a lower resolution, 30 FPS, or the other lens. Use good lighting because extremely dark scenes can also challenge camera exposure timing.

### The phone becomes warm

Camera capture and hardware encoding still consume power. Start the stream and lock the phone so its display and preview rendering turn off, remove a thick case if necessary, and avoid charging in an unusually hot environment. Android thermal protection remains in control.

### `OBS PhoneCam server is already running`

Use the existing server console or close it with `Ctrl+C` before starting another. If a console was forcibly terminated, wait briefly and run the launcher again.

### The old PocoCam app is still on the phone

OBS PhoneCam is a separate application (`com.local.obsphonecam`) and does not replace the original PocoCam install. Both work with this server. When you no longer need the old app, uninstall it (see [Uninstall](#uninstall)).

### macOS says the launcher cannot be opened

If macOS Gatekeeper or quarantine blocks `install_and_run.command` or `run_server.command` after a ZIP download, either right-click the file and choose **Open**, or clear the attribute once in Terminal:

```bash
xattr -dr com.apple.quarantine .
chmod +x scripts/macos/*.sh scripts/macos/*.command
```

## Build the APK from source

The prebuilt APK is included, so rebuilding is optional.

From PowerShell (Windows):

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\windows\build.ps1
```

From Terminal (macOS):

```bash
./scripts/macos/build.sh
```

`build.ps1` and `build.sh` keep everything project-local. They download JDK 17, Gradle 8.9, Android command-line tools, Platform-Tools, API 35, and Android Build Tools 35 into `.tools`, accept the required SDK licenses, build the release variant, and copy the result to:

```text
dist/OBSPhoneCam.apk
```

The project uses Android Gradle Plugin 8.7.3, Java 17, `minSdk 26`, and `targetSdk 35`.

> [!NOTE]
> The included personal-use APK is signed with the standard Android debug certificate using APK Signature Scheme v2. It is suitable for direct installation, but it is not a Play Store production signing setup. A source build made on another computer (including a Mac) will normally use a different debug key; uninstall the prebuilt app before installing that independently built APK, or configure one consistent private signing key.

## Repository layout

The repository is organized as modules:

- **`android/`** — the Android application (a Gradle project; `android/app/` is the application module that builds `OBSPhoneCam.apk`)
- **`relay/`** — the Python USB receiver and MPEG-TS relay for OBS (`server.py`, standard library only)
- **`scripts/macos/`** — macOS launchers (`setup.sh`, `install_and_run.sh` / `.command`, `run_server.sh` / `.command`, `build.sh`)
- **`scripts/windows/`** — Windows launchers (`setup.ps1`, `install_and_run.bat`, `run_server.bat`, `build.ps1`)
- **`dist/`** — the packaged, installable `OBSPhoneCam.apk`

```text
.
├── README.md
├── .gitignore
├── android/
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradle.properties
│   └── app/
│       ├── build.gradle
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/local/obsphonecam/
│           │   ├── MainActivity.java
│           │   ├── CameraService.java
│           │   └── CameraCatalog.java
│           └── res/values/styles.xml
├── relay/
│   └── server.py
├── scripts/
│   ├── macos/
│   │   ├── setup.sh
│   │   ├── install_and_run.sh / install_and_run.command
│   │   ├── run_server.sh / run_server.command
│   │   └── build.sh
│   └── windows/
│       ├── setup.ps1
│       ├── install_and_run.bat
│       ├── run_server.bat
│       └── build.ps1
└── dist/
    └── OBSPhoneCam.apk
```

> `.tools/` is created automatically by the launchers (JDK, Gradle, Android SDK, Platform-Tools) and is excluded from Git.

## Technical details

### Android

- Native Java with no third-party Android libraries.
- Android foreground service type `camera`, allowing capture to continue while locked.
- Preview surface is removed when the screen/Activity pauses; the encoder surface stays active.
- Partial wake lock exists only while streaming and is released on Stop/error.
- Camera2 preview and encoder surfaces avoid CPU pixel conversion.
- MediaCodec hardware H.264 surface encoder.
- User-selectable resolution and frame rate from the modes the phone actually exposes; 30 or 60 FPS and larger modes where supported.
- Quality presets and custom bitrate, plus rate control, processing mode, and video stabilization when the hardware reports them.
- Front/rear swap rebuilds the camera and encoder pipeline in place; the USB socket, wake lock, and foreground notification stay up, and the new SPS/PPS reach OBS in band.
- Mode-aware 4–40 Mb/s CBR target, one-second keyframe interval, no B-frames.
- The on-screen preview is capped at 720p even when encoding larger modes, avoiding unnecessary display/GPU work.
- Encoder priority, operating-rate, and low-latency hints.
- Bounded 16-packet network queue; congestion drops stale frames and requests a new keyframe instead of accumulating delay.

### USB protocol

- The app connects only to `127.0.0.1:8765`.
- `adb reverse` maps that phone-local endpoint to the PC relay over USB.
- Each encoded access unit carries its size, MediaCodec presentation timestamp, and flags.
- The phone monotonic clock handshake lets the server estimate sensor-to-PC delay.
- In-stream mode notices (flag bit 16, carried inside an Annex-B filler NAL) keep the relay's status geometry in sync after a camera swap; the relay also accepts the original `POCOCAM/1` and `POCOCAM/2` hello banners for backward compatibility.

### Python/OBS relay

- Python standard library only; no pip packages.
- Converts AVCC NAL units to Annex B when required.
- Adds access-unit delimiters and cached SPS/PPS to keyframes.
- Generates PAT, PMT, PTS, and PCR in MPEG-TS.
- Sends seven 188-byte TS packets per UDP datagram.
- Provides a bounded, keyframe-aware HTTP fallback queue.
- Opens only localhost ports `8765`, `8766`, and UDP destination `5600`.

## Privacy and security

- No analytics, ads, account, cloud service, or Wi-Fi server.
- No Android microphone, location, contacts, storage, or accessibility permission.
- `CAMERA` captures video; `INTERNET` is used only for the phone-local ADB tunnel.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CAMERA` allow Android to keep camera capture active while locked.
- `WAKE_LOCK` keeps the streaming thread alive while the display sleeps and is held only during an active stream.
- PC listeners bind to `127.0.0.1`, not the LAN.
- The setup script downloads Platform-Tools only from Google's HTTPS download URL.
- USB debugging grants a trusted PC powerful access to the phone. Disable it or revoke the PC authorization when you no longer need it.

## Uninstall

Uninstall **OBS PhoneCam** from Android Settings, or while the phone is connected run (Windows):

```powershell
.\.tools\platform-tools\adb.exe uninstall com.local.obsphonecam
```

or (macOS):

```bash
./.tools/platform-tools/adb uninstall com.local.obsphonecam
```

If the original PocoCam app is still installed from earlier use, remove it as well with the same commands using `com.local.pococam`.

Delete the project directory to remove the Python relay and all project-local tools. Nothing is installed as a system service or driver.

## Known limitations

- The supplied launch/setup scripts cover Windows (`.bat`/`.ps1`) and macOS (`.sh`/`.command`). The Python relay itself is platform-independent; other operating systems would need their own Platform-Tools download step.
- The stream must initially be started from the visible Activity; it can then continue with the phone locked.
- Some aggressive OEM battery managers may require OBS PhoneCam battery mode to be set to No restrictions.
- Available resolution/FPS pairs depend on Camera2 support for the selected lens; vendor camera apps can expose modes that third-party Camera2 apps cannot.
- External microphone alignment requires one fixed OBS sync-offset calibration.
- This is an OBS Media Source, not a native system-wide webcam driver.
- iOS/iPhone is not supported; the USB transport is Android-only.

## Credits and license

**Credits.** OBS PhoneCam is a derivative of [**Phone-to-OBS-Studio-as-Webcam**](https://github.com/FurkanGozukara/Phone-to-OBS-Studio-as-Webcam) by [**Furkan Gözükara**](https://github.com/FurkanGozukara). The original design — the Android Camera2 capture app with its foreground streaming service, the USB/ADB protocol, and the Python MPEG-TS OBS relay — is his work, and it is excellent. If you find this project useful, please also star and support the original repository.

**License.** The original repository did not declare a license file, so the original work remains “all rights reserved” by its author; this fork therefore does not re-license the original code. The fork's own modifications (renaming, macOS scripts, camera swap, quality/advanced settings) are shared openly for personal use, in the same spirit as the original project. If you are the original author and would like this derivative adjusted, credited differently, or taken down, please open an issue here and it will be handled promptly.
