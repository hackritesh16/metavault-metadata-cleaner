# MetaVault agent instructions

## Start here (keep token use low)
- Read this file first.
- Read only the relevant source files for the task you were asked to do.
- Do NOT reread the entire repository, README, or `docs/METAVAULT_MASTER_PROMPT.md` for every task.
- Treat the master prompt as a reference library. Open only the relevant section when a requirement is unclear or the task explicitly asks for a full product review.
- Check the latest CI run only when changing build/configuration code or when asked to verify a build.

## Product essentials
MetaVault is a privacy-first Android metadata cleaner (beta). Code: `android/app/src/main/java/com/metavault/cleaner/` — `MetadataEngine.kt` (view/clean/edit logic) and `MainActivity.kt` (single-file Compose UI).

Implemented, with test level:
- JPG/PNG/WEBP: view, edit and clean EXIF via ExifInterface without re-encoding. Optional "convert" re-encodes. XMP/IPTC blocks are not covered. Not device-tested.
- MP3: ID3v2/ID3v1 removal. Unit-tested.
- DOCX/XLSX/PPTX: core/app properties cleared. Unit-tested.
- PDF: Info dictionary and XMP removed with PdfBox-Android. Experimental; not unit-tested or device-tested.
- Other audio and video: metadata view only. Video cleaning is not implemented.
- In-app gallery via MediaStore. PDF/Office files are listed only with optional all-files access.
- Saving: "Save as new" is the default (images go to Pictures/MetaVault). "Overwrite original" requires an explicit confirmation dialog and first stores a backup in app storage; History can restore or delete it. Not device-tested.
- CI (`Android APK Build`) runs on `main`, `feature/**` and PRs to `main`; it builds the debug APK, uploads it as an artifact, then runs unit tests.
- Android SAF file selection is supported. Full-storage access must remain optional.
- Never claim a feature works unless it exists and has been tested.

## Efficient workflow
1. Identify the single requested task.
2. Inspect only the files directly involved and their immediate dependencies.
3. Make the smallest complete change; avoid unrelated refactors and broad audits.
4. Run the narrowest relevant test/build. Run a full Android build for Kotlin, Gradle, manifest, resources or navigation changes.
5. Report changed files, test/build result, and remaining limitation in a few bullets.

## Non-negotiable safety
- Keep file processing on-device; do not add cloud uploads or unnecessary telemetry.
- Preserve originals. Never overwrite without explicit user confirmation and a safe recovery path.
- Use least-privilege Android permissions and prefer SAF/MediaStore.
- Never fake metadata removal, success states, file counts, or format support.
- Do not commit secrets, signing keys, private user files, or generated personal media.

## Detailed reference
`docs/METAVAULT_MASTER_PROMPT.md` contains the full product vision and future requirements. Consult only the relevant parts when needed; it is not a mandatory full-read on each task.
