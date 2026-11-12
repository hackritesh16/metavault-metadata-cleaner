# MetaVault agent instructions

## Start here (keep token use low)
- Read this file first.
- Read only the relevant source files for the task you were asked to do.
- Do NOT reread the entire repository, README, or `docs/METAVAULT_MASTER_PROMPT.md` for every task.
- Treat the master prompt as a reference library. Open only the relevant section when a requirement is unclear or the task explicitly asks for a full product review.
- Check the latest CI run only when changing build/configuration code or when asked to verify a build.

## Product essentials
MetaVault is a privacy-first Android metadata cleaner. Current Android implementation is an MVP:
- JPG, PNG and WEBP metadata stripping preserves encoded image data; explicit format conversion re-encodes pixels.
- Cleaned images are saved as new files; originals are not overwritten.
- MP3 ID3 and selected DOCX/XLSX/PPTX properties can be cleaned. Video, PDF and other audio cleaning are not implemented. See the engine and tests for exact limitations.
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
