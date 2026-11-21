# AdSkip 1.2.0

Android intelligent ad-skip assistant. Full source package: [`AdSkip-1.2.0-source.zip`](./AdSkip-1.2.0-source.zip).

## Build in GitHub Actions

The `Android CI` workflow extracts the source archive and runs JVM unit tests, Android Lint, and `assembleDebug` with JDK 17, Gradle 8.9, and Android SDK 35. Download the `AdSkip-1.2.0-debug-apk` artifact from the successful workflow run to install the Debug APK.

## Local source

Download and extract `AdSkip-1.2.0-source.zip`. Open the extracted `AdSkip/` directory as an Android Gradle project. See its `README.md` for module details, permissions, privacy notes, testing instructions, and known DNS limitations.

**Note:** This is a Debug test build. It is not a production/release-signed APK. Android Accessibility and VPN permissions must be enabled explicitly by the device owner.
