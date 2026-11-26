# AdSkip 1.4.6 — DNS domain rule import and export

Android intelligent ad-skip assistant.

## Canonical source

The maintained, buildable Android Gradle project is in [AdSkip/](./AdSkip/). Use this directory as the current source of truth. The original [AdSkip-1.2.0-source.zip](./AdSkip-1.2.0-source.zip) is retained as the initial uploaded snapshot; it is not the canonical location for subsequent fixes.

To download the current source, use **Code → Download ZIP** on this repository, then open the extracted `AdSkip/` directory as an Android Gradle project.

## Build in GitHub Actions

The **Android CI** workflow runs JVM unit tests, Android Lint, and `:app:assembleDebug` with JDK 17, Gradle 8.11.1, and Android SDK 36. Download the `AdSkip-1.4.6-debug-apk` artifact from a successful workflow run; test and lint reports are uploaded separately.

## Local build

Open `AdSkip/` in Android Studio and build the `app` debug variant. The project uses Java 17, minSdk 26, and targetSdk 36.

## 1.4.6 DNS domain rule import/export

- Import TXT files containing one domain per line, hosts-file rows, or simple `||domain^` rules. Input is normalized and deduplicated.
- Import merges into manual DNS rules transactionally; it never replaces existing rules, changes existing disabled entries, or deletes downloaded subscriptions.
- Export writes currently enabled manual DNS rules as a portable UTF-8 TXT file. Subscription feed rules remain managed by the subscription manager.
- Imports are limited to 5 MB and 5,000 unique domains. Conditional Adblock rules are rejected rather than broadened into global DNS blocks.

## 1.4.5 app scan policy

- System apps and updated system apps are excluded from Accessibility, OCR, splash/countdown probes, Shizuku/ADB foreground handling, and manual package diagnostics. Unknown package metadata is rejected.
- An optional, default-off **user-launched-only** mode requires a launcher icon click to resolve uniquely to one enabled, non-system app package. A duplicate or unresolved icon label does not grant permission.
- Authorization applies only while that exact package is the foreground app and is revoked as soon as another package becomes foreground. Background apps and notification/deep-link returns cannot reuse old authorization.
- After enabling the mode, return to the home screen/app drawer and launch the target app by tapping its icon. The target app must remain in the foreground for AdSkip to scan it.

## Permissions and limitations

Android Accessibility and VPN permissions must be enabled explicitly by the device owner. DNS filtering uses Android's single VPN slot and does not filter DoH/DoT, hard-coded IPs, or all in-app advertising.

**Note:** CI produces a Debug test APK, not a production/release-signed APK.