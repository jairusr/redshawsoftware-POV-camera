# Redshaw Software POV Camera

A focused native Android application for hands-free POV photos and videos from camera-equipped VITURE glasses.

## Features

- 1920×1080, 30 fps live preview from the VITURE Camera Provider API;
- original MJPEG frames saved as JPEG photos;
- GPU-accelerated H.264 MP4 recording;
- mono AAC audio at the microphone's native rate, probing a detected USB microphone and automatically falling back to the Android host microphone if the USB stream is silent;
- USB attach, permission, disconnect, and reconnect handling;
- a compositor-friendly preview that remains inside SpaceWalker's managed Android display;
- scoped-storage output under `DCIM/RedshawSoftwarePOVCamera`.

The VITURE camera stream has a fixed 1920×1080/30 fps MJPEG format. This application deliberately provides only the controls supported by that stream: photo and video capture.

## Obtain the VITURE SDK

VITURE's Android Glasses SDK is proprietary and is not distributed here. Apply for developer access and obtain your own copy from VITURE:

- [VITURE Android integration guide](https://www.viture.co.nz/developer/glasses-sdk/glasses#android-integration-guide)
- [VITURE camera streaming documentation](https://www.viture.co.nz/developer/glasses-sdk/glasses#camera-streaming)

After receiving `VITURE_XR_Glasses_SDK_for_Android.zip`, install its local runtime library:

```bash
./scripts/install-viture-sdk.sh /path/to/VITURE_XR_Glasses_SDK_for_Android.zip
```

The installer copies only `libglasses.so` into a Git-ignored `jniLibs` directory. Never commit or redistribute that file. See [`VITURE-SDK-NOTICE.md`](VITURE-SDK-NOTICE.md).

## Build without Unity

This is a standard Android Gradle project. Unity is not used or required.

Requirements:

- Android Studio or JDK 17;
- Android SDK 36;
- Android NDK 27.2.12479018 or a compatible r27 release;
- CMake 3.22.1;
- an arm64 Android host with USB host support.

Build and install:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Wireless ADB works normally. On first launch, approve the Android camera and USB permissions. The microphone permission is requested when recording begins.

The app intentionally does not register as an automatic USB-launch handler. Open it from SpaceWalker after connecting the glasses; reconnecting the camera will not unexpectedly replace the current application.

The open-source project also compiles without `libglasses.so`, but the resulting APK cannot open the glasses camera until the SDK is installed and the APK rebuilt.

## SpaceWalker operation

Use the Neckband's **SpaceWalker mode**, not Android Original Mode. In SpaceWalker's Quick Settings:

- enable **Smooth Follow** so the camera screen follows your head instead of remaining spatially anchored;
- enable **Hand Gestures** (or long-press the Neckband Settings button) for the SpaceWalker cursor and pinch-to-click controls;
- double-press the Settings button to recenter if required.

The application is an ordinary Android activity rendered into SpaceWalker's managed virtual display. It does not create an Android `Presentation` on the physical HDMI glasses display. A direct presentation can provide a head-locked image, but it bypasses SpaceWalker's composition and therefore removes its hand-gesture cursor and input routing.

Camera streaming stops whenever the activity leaves the foreground, preventing a background preview from consuming resources needed by SpaceWalker's tracking compositor.

## Hardware validation

The standalone build has been exercised over wireless ADB on a VITURE Neckband Pro (V1231, Android 13):

- live preview and JPEG photos: upright 1920×1080 output;
- video: 1920×1080 H.264 at 29.65 fps over a 5.47-second verification capture;
- audio: 16 kHz mono AAC with a valid non-zero waveform (−17.3 dB peak in the verification capture);
- microphone routing: the exposed `USB-Audio - VITURE Microphone` returned digital silence on this device, so the preflight automatically selected the working `V1231` microphone before official A/V timestamps began.
- SpaceWalker integration: the activity was confined to a launcher-owned 1920×1080 virtual display, with no application window or surface on the physical 3840×1200 HDMI display; Android focused pointer input on the application display.

Other VITURE hardware may provide a working USB microphone; when a non-zero USB signal is detected, the application retains that route.

## Supported camera IDs

| Glasses | Camera USB ID |
| --- | --- |
| Luma Pro, Luma Cyber, Luma Ultra | `0c45:636b` |
| Beast | `0c45:6368` |

## Attribution

The early prototype was informed by studying [Open Camera](https://opencamera.org.uk/). The published standalone application contains no Open Camera application source or artwork. See [`ATTRIBUTION.md`](ATTRIBUTION.md).

## License

Redshaw Software POV Camera is distributed under the GNU General Public License, version 3 or later. See [`LICENSE`](LICENSE).
