# MetaVault — Master Product & Engineering Brief

> Source of truth for AI coding agents and contributors. Read before changing code. This describes the target product, not proof that every feature already exists. The actual code and tests determine current implementation status.

## 1. Mission
Build **MetaVault — Metadata Cleaner & Privacy Vault**, a privacy-first Android application for browsing device files, inspecting metadata, removing supported metadata locally, and safely exporting cleaned copies.

Tagline: **Clean • Protect • Stay Private**

Core promise: files stay on the device. Never upload user files or metadata to a server.

## 2. Non-negotiable engineering rules
1. Never claim a format or metadata field is supported unless implementation and tests prove it.
2. Never show success until output is written and verified.
3. Preserve originals by default. Never overwrite without separate explicit confirmation and a safe recovery strategy.
4. Use least Android permissions. Prefer MediaStore and Storage Access Framework (SAF). All-files access is optional, never forced, and may be unsuitable for Play distribution.
5. Keep processing on-device. Do not add analytics, upload endpoints, or telemetry containing filenames, GPS, or metadata.
6. Treat filenames, MIME types, extensions, and provider results as untrusted. Validate content and handle malformed files safely.
7. Do not silently discard streams, pages, transparency, rotation, subtitles, chapters, attachments, or other content.
8. Use bounded memory and background work for large files. Clean temporary files on success, failure, and cancellation.
9. Explain limitations honestly. Metadata removal is format- and container-specific; never promise universal removal.
10. Make small, buildable changes. Run tests and Android build after meaningful changes; report exact results.

## 3. Product identity and visual design
- Product name: MetaVault. Tagline: Clean • Protect • Stay Private.
- Premium modern privacy/security identity.
- Deep black/navy surfaces, mint/neon-green and electric-blue accents.
- Shield, lock, stylized M, subtle orbit/glow motif.
- Dark glass-inspired cards, restrained effects, rounded surfaces, accessible contrast.
- Lightweight motion; respect reduced-motion preferences where practical.
- Adaptive and round Android launcher icons using the shield/M mark; keep it legible at small sizes.

## 4. First-run and permissions
- Welcome screen explains local processing and why access is requested.
- Request only permissions needed for the selected action/category.
- Support Android 8.0 (API 26) onward, adapting to platform changes.
- Handle Android 13 granular media permissions and Android 14+ partial/selected photo access.
- If permission is denied, offer SAF picker fallback and explain limitations; do not repeatedly nag or block the app.
- MANAGE_EXTERNAL_STORAGE is optional and only for a justified feature. Explain settings handoff and preserve normal SAF operation without it.

## 5. Main dashboard and navigation
Navigation: Home, Browse, History, Settings.
Dashboard: Scan Device / Browse Files, supported file counts only when actually queried and accessible, recent activity, Quick Clean, and privacy explanation.
Categories: Photos, Videos, Audio/Music, PDF documents, Other documents.
Each category needs count, empty state, permission state, and supported/unsupported status. Never imply inaccessible storage has been scanned.

## 6. File browsing and selection
- MediaStore for accessible image, video, and audio media.
- SAF OpenDocument for PDFs and user-selected documents.
- Preserve URI grants only as long as needed.
- Search, sort by name/size/date, thumbnails, single/multi-select, and file details.
- Query human-readable names with OpenableColumns.DISPLAY_NAME; do not display provider document IDs as filenames.
- Handle cloud providers, removable storage, revoked grants, missing files, and provider errors.
- Never scan all storage without a clear user action and appropriate access.

## 7. Metadata inspection
Show only metadata actually read. Distinguish embedded metadata from technical media properties. Unavailable fields must be labelled unavailable, not invented or inferred.

### Images
Inspect supported EXIF, GPS, camera make/model, timestamps, orientation, software, embedded thumbnails, and relevant PNG/WebP metadata chunks where libraries allow.

### Video
Inspect supported container and stream metadata: creation time, location tags, encoder, stream properties, rotation, subtitles, chapters, attachments, and tracks where available.

### Audio
Inspect supported tags/chunks such as ID3, Vorbis comments, FLAC blocks, MP4 tags, and embedded artwork, depending on implemented formats.

### PDF
Inspect Info dictionary and XMP fields where supported: author, title, subject, creator, producer, creation/modification dates.

## 8. Cleaning engines — implement independently
Create clear interfaces such as MetadataInspector, MetadataCleaner, CleaningResult, FormatSupport, and per-format engine implementations.

Each engine reports supported formats/metadata classes, fields found/removed/remaining/unverified, content preservation, output verification, and clear failure reasons.

### 8.1 Photo engine
Initial formats: JPEG, PNG, WEBP.
- Remove supported EXIF, GPS, XMP, comments/text chunks, thumbnails, and other supported metadata.
- Preserve dimensions and transparency where possible.
- Prefer reliable lossless metadata/chunk removal.
- If decoding/re-encoding is used, label it clearly and explain pixels/quality/file size may change.
- Quality controls only where applicable.
- Verify output decodes, dimensions are correct, and targeted metadata is absent.

### 8.2 Video engine
Candidate formats: MP4 and MOV first; expand only with tests.
- Evaluate MediaExtractor/MediaMuxer and maintained local remux libraries such as FFmpeg where licensing/build size permit.
- Preserve supported audio/video streams and content.
- Handle rotation, timestamps, subtitles, chapters, attachments, codec compatibility.
- Avoid transcoding unless user explicitly chooses it and implications are explained.
- Verify playback, stream properties, and targeted metadata.
- Report container fields that cannot be removed.

### 8.3 Audio engine
Start with an explicit tested set such as MP3/ID3, FLAC, OGG/Vorbis, or M4A only after choosing compatible libraries.
- Remove supported tags, comments, artwork, and metadata blocks.
- Preserve encoded audio payload where possible; avoid re-encoding.
- Verify playback and inspect tags after writing.
- Document unsupported tags/formats.

### 8.4 PDF engine
Use a maintained Android-compatible PDF library; evaluate compatibility, maintenance, and license.
- Remove supported Info dictionary and XMP metadata.
- Preserve pages, page sizes, annotations, forms, and attachments unless user explicitly requests otherwise.
- Warn that modifying signed PDFs invalidates digital signatures.
- Verify PDF opens and page count/content are preserved.
- Do not claim sanitization of embedded files, scripts, annotations, or hidden content unless separately implemented and tested.

### 8.5 Other documents
Office formats (DOCX/XLSX/PPTX/ODT etc.) are future scope until format-specific sanitizers and tests exist. Never route them through an unrelated cleaner.

## 9. Cleaning workflow and export
1. User selects files.
2. Inspect and show available metadata.
3. User chooses supported cleaning options.
4. Explain exact operation and limitations.
5. Process locally with progress and safe cancellation.
6. Write a new destination by default.
7. Reopen/inspect and verify output.
8. Show truthful report and Open/Share/Save another copy actions.

Report source/output names, sizes, fields removed, fields remaining/unverified, engine, verification status, and output location.

Original replacement is optional advanced behavior:
- Explicit confirmation naming the original.
- SAF write access or Android media write-request flow.
- Recoverable backup/temp output where possible.
- If provider cannot safely replace, refuse and offer Save as new.
- Never delete source before verified replacement exists.

## 10. History
Store minimum local history. Avoid retaining GPS or extracted metadata. Allow clearing history. Do not retain original media copies. Explain history behavior.

## 11. Settings
Theme, default output behavior/folder, relevant quality preference, permission status/explanation, clear temporary files/history, privacy information, About/version, supported formats.

## 12. Architecture and technical direction
Current native stack: Kotlin, Jetpack Compose, AndroidX Activity/Lifecycle, Coroutines, MediaStore, SAF.
Separate UI/navigation, permission coordinator, media catalog/document picker, metadata inspection, per-format cleaning engines, export manager, history repository, and temporary-file/privacy manager.
Keep work off the main thread. Stream large files rather than loading everything into memory. Use scoped storage correctly. Add dependency injection only when useful.

## 13. Error handling and accessibility
Explicit states for no files, denied/partial access, unsupported format, corrupt/password-protected file, insufficient storage, provider unavailable, cancellation, processing failure, and verification failure.
Support TalkBack, contrast, touch targets, scalable text, progress announcements, and status indicators not based on color alone.

## 14. Tests and acceptance criteria
Test permission granted/denied/partial access; SAF URI access/revocation; MIME validation; corrupt/oversized files; metadata inspection against known fixtures; removal of every claimed field; output decodability/playback/openability; image dimensions/transparency; video stream preservation; audio payload/playback; PDF page/content preservation and signature warning; original unchanged by default; replacement recovery; temp cleanup; and no network transfer during processing.

A feature is complete only when implementation, tests, UI state, output verification, and documentation agree.

## 15. Build and delivery
Maintain a buildable Android Studio project. Keep JDK/Gradle/AGP compatible. Maintain GitHub Actions debug APK builds and artifact upload. Document installation/build steps. Add release signing configuration without committing secrets. Record dependency licenses. Update README with the real support matrix.

## 16. Current repository baseline — verify before changing
Baseline currently includes a browser image-cleaning workflow, native Kotlin/Compose Android starter, Android SAF picker/save flow, Android image re-encoding for JPG/PNG/WEBP, dark glass-inspired UI, vector shield/M adaptive launcher icon, and GitHub Actions debug APK workflow.

Known gaps at baseline:
- Native Android automatic category browsing/library is not complete.
- Video, audio, PDF, and Office metadata cleaning are not implemented.
- Original replacement is not implemented; Save as new is the safe behavior.
- Current Android image path re-encodes and is not byte-for-byte lossless.
- Continued testing, security review, and feature completion are required.

This baseline can become outdated. Inspect current source and latest CI run before relying on it.

## 17. How an AI coding agent should work here
When given repository access:
1. Read /AGENTS.md, this document, README.md, and android/README.md.
2. Inspect actual code, issues, and latest GitHub Actions build.
3. Identify the highest-priority incomplete requirement and dependencies.
4. Implement a small vertical slice that can be built and tested.
5. Do not rewrite working code wholesale or add dependencies without evaluating maintenance, compatibility, license, size, and security.
6. Add tests and update support documentation with each feature.
7. Run build/tests and fix failures where feasible.
8. Review permissions, data handling, temp files, and failure paths.
9. Commit focused changes with descriptive messages.
10. Report changed files, actual features, tests/build outcome, artifact link if available, limitations, and next task.

Priority: build reliability and permission/navigation correctness; media library; robust photo inspection/removal; video engine; audio engine; PDF engine; history/settings/accessibility/security; release preparation.

This brief does not authorize destructive changes, overwriting user data, exposing secrets, or claiming unsupported capabilities.
