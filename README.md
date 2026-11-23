# AdSkip 1.4.5 — system-app exclusion and user-launched foreground mode

Android intelligent ad-skip assistant.

## Canonical source

The maintained, buildable Android Gradle project is in [AdSkip/](./AdSkip/). Use this directory as the current source of truth. The original [AdSkip-1.2.0-source.zip](./AdSkip-1.2.0-source.zip) is retained as the initial uploaded snapshot; it is not the canonical location for subsequent fixes.

To download the current source, use **Code → Download ZIP** on this repository, then open the extracted `AdSkip/` directory as an Android Gradle project.

## Build in GitHub Actions

The **Android CI** workflow runs JVM unit tests, Android Lint, and `:app:assembleDebug` with JDK 17, Gradle 8.11.1, and Android SDK 36. Download the `AdSkip-1.4.5-debug-apk` artifact from a successful workflow run; test and lint reports are uploaded separately.

## Local build

Open `AdSkip/` in Android Studio and build the `app` debug variant. The project uses Java 17, minSdk 26, and targetSdk 36.

## Permissions and limitations

Android Accessibility and VPN permissions must be enabled explicitly by the device owner. DNS filtering uses Android's single VPN slot and does not filter DoH/DoT, hard-coded IPs, or all in-app advertising.

**Note:** CI produces a Debug test APK, not a production/release-signed APK.