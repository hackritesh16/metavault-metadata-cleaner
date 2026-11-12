# MetaVault Android

Existing native Kotlin and Jetpack Compose app, package `com.metavault.cleaner`.

## Build

Open `android/` in Android Studio, install SDK Platform 35 and use JDK 17.
With Gradle 8.9 installed, run from the repository root:

```sh
gradle -p android assembleDebug testDebugUnitTest lintDebug
```

Min SDK: 26. Compile/target SDK: 35. Debug APK:
`android/app/build/outputs/apk/debug/app-debug.apk`.
The Android APK Build GitHub workflow uploads `MetaVault-debug-apk` and runs unit tests.

## Current features

- Home, Browse, Cleaner, History and Settings tabs, plus category screens.
- Neutral transparent glass, eight themes, saved custom photo background and sliding navigation.
- On-device metadata inspection and supported cleaning; no file upload endpoint.
- Safe new-copy exports; optional original overwrite requires confirmation and a private backup.
- Session-only export history and persistent original backups with restore/delete controls.

| Format | Current support | Limits |
| --- | --- | --- |
| JPEG | Inspect, edit selected EXIF, strip metadata blocks | Keeps orientation and trailing data; no invisible watermark removal |
| PNG/WebP | Inspect, edit supported EXIF, strip supported container metadata | Keeps encoded pixels; trailing data is reported/retained |
| Image conversion | JPG/PNG/WebP re-encoding | Pixels/quality can change |
| MP3 | Inspect via Android; strip ID3v1/v2 | APE and other tags are outside this cleaner |
| DOCX/XLSX/PPTX | Inspect and clear selected core/application properties | Not complete document sanitization; dates, revisions, custom properties and embedded content may remain |
| Video/other audio | Inspect fields exposed by Android | Cleaning unsupported |
| PDF | Select files | Inspection/cleaning unsupported |

## Permission and navigation flow

Launching the app does not request library or location permissions. Open a media
category and tap **Allow access** to see an explanation, then continue to Android's
permission dialog. Android 13+ requests only the selected category. Android 14+
selected visual access is labelled, with **Manage access** to select more files.
Android 8–12 use the platform's shared storage read permission.

**File picker** remains available even when library access is denied or only some
files are allowed. Permanent denials can be changed through **App permission settings**.
The app reloads accessible media on return to the foreground. Counts describe
accessible files, not a scan of inaccessible storage (queries cap each category at 5000).

PDF/Office selection through SAF needs no broad storage permission. Optional
all-files access on Android 11+ can populate those categories. Android 10 uses SAF;
Android 9 and below can list documents if legacy read access was already granted.

Location metadata access is a separate explicit action in the photo metadata
screen. It reads location stored in the image, not the device's live location.
After granting it, metadata reloads. Denial does not block picking or cleaning.

Android Back returns from a category to Browse, from About to Settings and from
other tabs to Home. During file operations it keeps the operation screen active.

## Verification and remaining work

Unit tests cover supported container behavior, malformed input, bounded reads and
permission policies for legacy, granular, denied and selected-only access. Build
and lint do not replace physical-device tests of permission dialogs, partial
selection, revoked access, background persistence, exports or backup restoration.

No universal metadata removal or Metadata2Go feature parity is claimed. Persistent
export history, search/sort/batch selection, video/PDF engines, full document
sanitization, conversion memory/orientation checks and release signing remain work.
