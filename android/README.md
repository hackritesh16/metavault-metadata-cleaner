# MetaVault Android

Native Android starter built with Kotlin and Jetpack Compose.

## Open in Android Studio
1. Download or clone this repository.
2. In Android Studio choose **Open** and select the `android` folder.
3. Install Android SDK Platform 35 if prompted.
4. Use JDK 17 and allow Gradle sync to finish.
5. Select the `app` run configuration and run on an Android device/emulator.

## Current Android MVP
- Native Compose multi-page UI with Home, Browse, Photos, Videos, Audio, PDF, Documents, Cleaner, History, Settings and About screens
- Premium dark neon-gradient and glass-card styling with consistent typography
- Android Storage Access Framework file picker and save dialog
- Local JPG, PNG and WEBP decode/re-encode to strip embedded image metadata
- Preserve-format preference and output conversion controls
- Original file is never overwritten and files are not uploaded

## Current navigation
The app has dedicated screens for Home, Browse, Photos, Videos, Audio, PDF files, Documents, Cleaner, History, Settings and About. The five primary destinations are available from bottom navigation; file-type pages open from Browse/Home.

## Limitations
The preserve-quality option is best-effort, not mathematically lossless. Image dimensions are retained; re-encoding may alter pixels/file size. The current Android app does not yet process video, audio, PDF or Office documents. Those file types are selectable for future support but are not cleaned by this MVP.

## Build
Android Studio / Gradle with JDK 17. Compile SDK 35, min SDK 26.
