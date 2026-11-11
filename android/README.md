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
- Premium dark styling with bundled fonts
- Android Storage Access Framework file picker, plus an on-device media library when permission is granted
- Metadata inspection of the selected file before anything is cleaned
- Local cleaning without re-encoding pixels or audio:
  - JPG, PNG and WEBP: EXIF fields such as camera, date, GPS, author and software
  - MP3: ID3v2 and ID3v1 tags
  - DOCX, XLSX and PPTX: author, last-modified-by, title, company, manager and similar document properties
- Cleaned output is written as a new file through the save dialog; the original file is never overwritten
- Files are never uploaded

## Current navigation
The app has dedicated screens for Home, Browse, Photos, Videos, Audio, PDF files, Documents, Cleaner, History, Settings and About. The five primary destinations are available from bottom navigation; file-type pages open from Browse/Home.

## Limitations
- Image cleaning does not cover XMP or IPTC blocks yet.
- MP3 cleaning does not cover APE tags yet.
- Office cleaning does not clear comments, tracked-change authors or custom document properties, and it keeps created/modified dates and revision history.
- Video and audio other than MP3 can be inspected but not cleaned. PDFs are not inspected or cleaned yet.
- The cleaning engine is in `app/src/main/java/com/metavault/cleaner/MetadataEngine.kt`, and its unit tests are in `app/src/test`. Each cleaning result reports what was actually removed.

## Build
Android Studio / Gradle with JDK 17. Compile SDK 35, min SDK 26.
