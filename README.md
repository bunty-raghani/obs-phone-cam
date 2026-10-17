# OBS PhoneCam — Phone to OBS Studio as Webcam

> **This project is built on top of [Phone-to-OBS-Studio-as-Webcam](https://github.com/FurkanGozukara/Phone-to-OBS-Studio-as-Webcam) by [Furkan Gözükara](https://github.com/FurkanGozukara).**
> The original Android app, USB/ADB transport, and Python MPEG-TS relay are his design and work; all credit for the original project belongs to the original author and repository, and this derivative would not exist without it.
> This repository is an enhanced, cross-platform fork: it adds full macOS support, live front/rear camera swapping while streaming, and advanced capture settings in the phone app (quality presets, custom bitrate, rate control, processing mode, stabilization). See [Features](#features).

**Use your Android phone as a low-latency camera in OBS Studio — over a plain USB cable.**

```
Phone: Camera2 capture → hardware H.264 encoder → USB (ADB)
PC:    Python relay (no re-encoding) → OBS Media Source (MPEG-TS)
```

- **No Wi-Fi, no cloud, no account** — video travels only through the USB cable.
- **No re-encoding on the PC** — measured phone-to-PC latency ≈ 90–200 ms; 720p60 was the fastest mode on the tested phones.
- **Creates an OBS Media Source** — it is not a system-wide virtual camera. For Zoom/Teams/browsers, feed the OBS output into OBS Virtual Camera.
- **Works with the screen off** — after the stream starts you can lock the phone and it keeps streaming.

## Supported systems

| | |
|---|---|
| Windows | Windows 10 / 11 |
| macOS | Apple Silicon and Intel |
| Phone | Any Android 8.0+ phone — any brand, no root required |
| Unsupported | iPhone / iOS (the USB transport is Android-only) |

Also needed: a USB **data** cable (not charge-only), [Python 3.10 or newer](https://www.python.org/downloads/) (on macOS, install from python.org — the built-in Python 3.9 is too old), and [OBS Studio](https://obsproject.com/download). The installers download Google's Android Platform-Tools (ADB) for you — nothing is installed system-wide.

## Features

- **Exactly the modes your phone really offers** — the app lists what your phone's camera driver exposes per lens: aspect ratios, resolutions (720p / 1080p / 4K…), and frame rates (30 / 60 / higher where supported). Unsupported combinations are hidden, never faked.
- **Live front ⇄ rear swap while streaming** — use the floating button in the app or the notification action; the USB link and the OBS source stay up and video resumes on the next keyframe.
- **Quality presets & advanced settings** — Basic / Balanced / High / Maximum presets (bitrate computed automatically) or a custom bitrate; advanced panel adds rate control (CBR/VBR), processing mode, and video stabilization when the phone's hardware reports them.
- **One-click setup scripts** for Windows and macOS — install the APK, create the USB tunnel, and start the relay.
- **Backward compatible** — phones still running the original PocoCam app work with this relay too.

## Setup

### Step 1 — phone (once)

1. **Settings → About phone**, tap **Build number** 7 times.
2. **Settings → System → Developer options**, enable **USB debugging**.
3. Connect the USB cable; if asked, select **File transfer** mode and tap **Allow** on the debugging prompt (tick "Always allow from this computer").

No root, no bootloader unlock.

### Step 2 — download

On this page: **Code → Download ZIP**, then extract it. Keep the folder intact — the scripts expect this structure. (Or clone the repository with `git clone <repo-url>`.)

### Step 3 — one-time install (phone connected and unlocked)

- **Windows:** double-click `scripts\windows\install_and_run.bat`
- **macOS:** double-click `scripts/macos/install_and_run.command` (if macOS blocks it: right-click → **Open**)

The first run downloads ADB if needed, installs `dist/OBSPhoneCam.apk` on the phone, creates the USB tunnel, and starts the relay.

### Step 4 — add it to OBS (once)

1. In a scene: **Sources → + → Media Source**.
2. Untick **Local File** and set:

| Property | Value |
|---|---|
| Input | `udp://127.0.0.1:5600` |
| Input Format | `mpegts` |
| Network Buffering | **0 MB** |
| Hardware decoding | Enabled |

The source goes live as soon as the phone starts streaming. If UDP will not play in your setup, use the fallback input `http://127.0.0.1:8766/stream.ts` with the same `mpegts` format (UDP is preferred for latency).

### Step 5 — stream

1. Open **OBS PhoneCam** on the phone, choose camera / resolution / frame rate, and tap **Start**.
2. Wait for **LIVE over USB** — you can now lock the phone; the display turns off but streaming continues.
3. Next time: connect the phone → run `run_server.bat` (Windows) or `run_server.command` (macOS) → open the app → **Start**.

To stop: tap **Stop** in the app and press `Ctrl+C` in the server window.

## Uninstall

1. **Phone:** Settings → Apps → OBS PhoneCam → **Uninstall**.
2. If the original **PocoCam** app is still installed from earlier use, uninstall it too — OBS PhoneCam is a separate app (`com.local.obsphonecam`) and does not remove it.
3. **PC:** delete the project folder — nothing was installed system-wide (no driver, no service).

Terminal alternative, with the phone connected:

```powershell
# Windows
.\.tools\platform-tools\adb.exe uninstall com.local.obsphonecam
```

```bash
# macOS
./.tools/platform-tools/adb uninstall com.local.obsphonecam
```

The old app's package is `com.local.pococam`.

## Quick troubleshooting

| Symptom | Fix |
|---|---|
| "No USB-debugging phone found" | Use a data cable; unlock the phone; choose **File transfer** mode; try another USB port; accept the debug prompt. |
| "Phone is unauthorized" | Tap **Allow** on the phone. No prompt? Developer options → **Revoke USB debugging authorizations**, then reconnect. |
| OBS shows nothing | Start the phone stream first, then the OBS source. Check `mpegts` and `udp://127.0.0.1:5600` (or the HTTP fallback). Wait ~1 second for a keyframe. |
| Video noticeably delayed | OBS **Network Buffering must be 0 MB** (not 16), use the UDP URL, keep hardware decoding on. Live numbers: `http://127.0.0.1:8766/status.json`. |
| FPS lower than selected | That exact mode may not be supported by the lens — try a lower resolution, 30 fps, or the other camera. |
| macOS blocks the launcher | Right-click the file → **Open**. Or in Terminal: `xattr -dr com.apple.quarantine . && chmod +x scripts/macos/*.sh scripts/macos/*.command` |
| Phone gets warm | Expected — capture and encoding use power. Locking the phone turns off the display and preview, which helps the most. |

## Tips

- **Lowest latency:** 720p60 with UDP and OBS buffering at 0 MB.
- Match **OBS → Settings → Video → Common FPS** to the phone's chosen rate (30 or 60).
- **External microphone:** add **Audio Input Capture** in OBS, then set a positive **Sync Offset** (Edit → Advanced Audio Properties) starting at the `capture_to_pc_ms` value shown in the server console (≈ +90–200 ms). Fine-tune with a hand-clap test.
- Phone audio is intentionally not captured, to avoid duplicating your microphone.

## Repository layout

| Folder | Contents |
|---|---|
| `android/` | Android app source (Gradle project; builds `OBSPhoneCam.apk`) |
| `relay/` | Python USB → MPEG-TS relay for OBS (standard library only) |
| `scripts/macos/` · `scripts/windows/` | One-click setup / launch / build scripts |
| `dist/` | Prebuilt `OBSPhoneCam.apk` |

### Rebuild the APK (optional)

The APK is included — rebuilding is only needed if you change the source.

- Windows: `powershell -ExecutionPolicy Bypass -File .\scripts\windows\build.ps1`
- macOS: `./scripts/macos/build.sh`

Everything (JDK, Gradle, Android SDK) is downloaded into `.tools/` inside the project folder.

<details>
<summary>Prebuilt APK SHA-256</summary>

```text
84B239138806A5A25528763D227ECE85C767B70D432C43636080B20483A73164
```

</details>

> The APK is signed with the standard Android debug certificate — fine for personal use, not a Play Store publishing setup. An APK you build on another machine uses a different debug key, so uninstall the shipped app before installing it.

## Credits and license

**Credits.** OBS PhoneCam is a derivative of [**Phone-to-OBS-Studio-as-Webcam**](https://github.com/FurkanGozukara/Phone-to-OBS-Studio-as-Webcam) by [**Furkan Gözükara**](https://github.com/FurkanGozukara). The original design — the Android Camera2 capture app with its foreground streaming service, the USB/ADB protocol, and the Python MPEG-TS OBS relay — is his work, and it is excellent. If you find this project useful, please also star and support the original repository.

**License.** The original repository did not declare a license file, so the original work remains “all rights reserved” by its author; this fork therefore does not re-license the original code. The fork's own modifications (renaming, macOS scripts, camera swap, quality/advanced settings) are shared openly for personal use, in the same spirit as the original project. If you are the original author and would like this derivative adjusted, credited differently, or taken down, please open an issue here and it will be handled promptly.
