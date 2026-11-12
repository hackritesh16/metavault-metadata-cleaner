# Android review — 12 November 2026

Baseline: `b0788e0192a133255fc1219fc48d71a1829cfdbd`, PR #9,
`feature/frosted-liquid-glass-ui`.

## Evidence and changes

The supplied device screenshot still shows rectangular patches within several
Browse cards. The previous ripple-order fix alone does not establish that the
rendering problem is resolved. Removed elevation/shadow layers from translucent
cards and navigation, retained rounded clipping, and separated click handling
from caller layout modifiers. Both cards in each Browse row now share a height.
This is a candidate rendering fix; confirmation on the affected device is required.

Glass uses neutral white reflections. Photo backgrounds now use a uniform black
scrim without colored aurora overlays, and secondary text is lighter. Background
photos decode to at most 2048 pixels on their longest dimension. Theme/photo
preferences remain stored locally. Navigation respects system navigation insets.

Processing disables changing tabs/files while an operation is in progress, so
an output cannot be accidentally associated with a newly selected source through
those controls. Restore/delete actions are disabled during backup restoration. SAF selection retains provider grants when available, supporting
later backup restoration.

File reads now enforce the 64 MiB limit during streaming even when a provider
reports no size. Office ZIP entries have an individual 64 MiB limit and retain
the cumulative 512 MiB limit, checked before excess data is buffered. Renamed
non-ZIP Office inputs fail instead of producing a successful empty archive.
Malformed/truncated ID3 headers fail instead of reporting zero remaining tags;
only ID3v2.4's footer flag adds a footer length. Regression tests cover these cases.

Kotlin explicitly targets JVM 17, matching Java and GitHub Actions. This also
avoids target mismatch when the local build runs under JDK 21. Thumbnail and
background loading now use keyed state/effects, clearing stale images on source
changes and avoiding the current Compose produceState lint errors. Navigation
animation reads its offset during layout instead of recomposing each frame.

## Current feature coverage and remaining work

- JPEG/PNG/WebP have container stripping engines and existing regression tests.
  Format conversion still re-encodes pixels. Trailing image data is retained and
  reported; pixel watermarks are outside metadata cleaning.
- MP3 cleaning covers ID3, not APE or every possible tag type. Playback/device
  verification is still required; unit tests check payload preservation.
- Office cleaning covers selected properties using the existing XML matching
  logic. It is not complete document sanitization: custom properties, alternate
  namespace prefixes, revision history and embedded files need separate work.
- Video and other audio are view-only. PDF inspection/cleaning remains unsupported.
  No universal Metadata2Go parity is claimed.
- MediaStore permissions, Android 14 selected-photo access, SAF fallback, gallery
  loading and backup-before-overwrite flows remain. Physical-device permission
  and restoration tests are still required.
- History currently keeps exports in memory for the session; backups persist in
  private app storage. Persistent export history is not implemented.
- Image conversion still needs bounded full-resolution decoding and orientation
  regression coverage. Provider write verification/recovery and process-death
  handling need further testing before production readiness.

## Device acceptance checks still required

1. On the affected device, Browse over a photo background: idle, press/release,
   scroll, navigate back; inspect all card interiors and rounded edges.
2. Try all eight themes and a large gallery photo; restart the app and verify
   preferences, readability and absence of blinking.
3. Check gesture and three-button navigation, large text and landscape.
4. Grant, deny and partially grant media access; open a file through SAF.
5. Clean representative supported fixtures, save copies, verify originals,
   overwrite only after confirmation, and restore a backup after restart.

## Verification of this change

- Local Gradle 8.9: `assembleDebug testDebugUnitTest lintDebug` completed successfully.
- 37 unit tests, zero failures/errors/skips. Lint: zero errors, nine warnings.
- `git diff --check` passed.
- Baseline GitHub Actions runs 43147236264 and 43147242452 both succeeded for
  `b0788e0`. These results do not validate this new local change.
- No physical device is connected. Android 35 and Android 29 software emulator
  startup attempts were blocked by their storage requirements in this workspace;
  no emulator or physical-device visual pass is claimed.
