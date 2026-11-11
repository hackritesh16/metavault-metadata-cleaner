# MetaVault — Agent Instructions & Product Roadmap

> Read this file before changing the app. This is the product plan and execution checklist.
> Implement the roadmap in small, independently testable phases. Do not attempt the entire suite in one change.

## 1. How to work (token-efficient)

- Read this file first, then inspect only the source files relevant to the current task.
- Work on the active feature branch or a task-specific feature branch. Never push directly to `main`.
- Do not modify unrelated pull requests or unrelated modules.
- Do not reread the entire repository or master prompt for every task.
- Implement ONE phase or a clearly bounded subtask at a time.
- Before coding, state the exact files/modules you expect to change and the acceptance criteria.
- After coding, run the narrowest relevant tests. For Kotlin, Gradle, manifest, resources, navigation or UI changes, run the Android build and relevant tests.
- Inspect the final diff for regressions, accidental deletions, secrets and unrelated changes.
- Report: what changed, files changed, tests/build actually run, pass/fail status, and known limitations.
- Never claim a feature, format, permission, metadata removal or build works unless implemented and verified.

## 2. Product vision

MetaVault is a privacy-first Android file metadata analyzer, editor, cleaner and file utility suite inspired by the capabilities of Metadata2Go.

The long-term goal is to support these product areas:
1. View Metadata — deep, structured metadata inspection.
2. Metadata Remover — safe, format-aware metadata removal.
3. Edit Metadata — edit and selectively delete metadata fields.
4. Analyze Image — image content analysis tools, where a suitable on-device or explicitly consented service is available.
5. PDF Parser — extract text, embedded images and fonts.
6. Extract Video Streams — extract video, audio and subtitle tracks.
7. PDF Asset Remover — remove selected PDF object types.
8. Compare Images — visual image comparison and quality metrics.
9. Compare Videos — video quality and frame comparison.
10. Compare PDFs — page-by-page document comparison.

Build a reliable core before expanding into advanced utilities. A polished UI must never disguise missing functionality.

## 3. Current implementation reality

Treat this section as a baseline, not a promise that every future feature exists:
- The Android app is an MVP.
- Current image handling has format-specific work for JPEG, PNG and WebP; verify the actual code and tests before extending it.
- Existing image cleaning may preserve encoded image data for supported paths, but unknown/private payloads can remain.
- C2PA/JUMBF parsing currently targets readable fields in supported PNG content; it does not cryptographically validate provenance.
- Video, audio, PDF, Office, comparison and advanced analysis tools must be treated as planned until implemented and tested.
- Android Storage Access Framework (SAF) selection should remain available. Broad/all-files access must not be mandatory for ordinary use.
- Preserve originals. Save cleaned/edited/extracted results as new files unless the user explicitly confirms a safe overwrite.
- Never upload user files to a server by default. Keep processing on-device unless a feature genuinely requires a remote service and the user explicitly consents.

## 4. Product-wide requirements

### Privacy and file safety
- Prefer SAF and MediaStore with least-privilege permissions.
- Explain why a permission is required and provide a usable limited-access path if denied.
- Never silently request broad storage access or bypass Android restrictions.
- Do not upload files, metadata, thumbnails or personal content without explicit consent.
- Do not log sensitive metadata such as GPS coordinates, personal names, document contents or device identifiers.
- Handle malformed, truncated, oversized and hostile files defensively. Set sensible memory, recursion, item-count and processing limits.
- Preserve the original file and report where the output was saved.
- Use temporary files safely and delete them after processing.
- Do not claim “100% metadata removed”. Report exactly what was detected, removed, preserved and not understood.

### Metadata correctness
- Separate metadata findings from image/document content properties.
- Show category, field name, value, source/container, data type and byte size when known.
- Preserve unknown chunks/segments unless there is a documented safe removal rule.
- Preserve essential rendering data such as ICC profiles and colour-related chunks by default.
- A clean operation must rescan the output and show remaining detected metadata.
- Distinguish “not found”, “not supported”, “not readable” and “not removed”.
- C2PA removal destroys provenance credentials. Explain this before destructive removal and never imply that reading a C2PA claim verifies its signature.
- Invisible watermarks, steganography and proprietary/private payloads are separate from ordinary metadata. Do not claim they are removed unless a dedicated tested detector/remover exists.

### UI and accessibility
- Use a polished, modern, premium Android interface with consistent typography, readable contrast, thoughtful spacing, dark theme support and restrained gradient/neon accents.
- Keep text readable; never use low-contrast black text on dark surfaces.
- Use clear file-type categories, loading/progress states, empty states, permission-denied states and actionable errors.
- Metadata details should be searchable, expandable and grouped by category. Long values should wrap or have a copy action.
- Support large text, screen readers, touch targets and landscape/responsive layouts.
- Do not use decorative animations that block file processing or reduce accessibility.

## 5. Roadmap — implement in this order

Each phase is a separate task/PR where practical. Check items only after code and tests prove completion.

### Phase 0 — Foundation, architecture and reliability
- [ ] Audit the existing app architecture and document the real current feature status.
- [ ] Define shared file models: file identity, MIME type, format, size, metadata finding, category, source block, data type and processing result.
- [ ] Create a format detector based on file signatures/content, not extension alone.
- [ ] Create a common scanner interface and a common cleaner interface with per-format handlers.
- [ ] Add safe streaming/large-file handling where possible; avoid loading huge files entirely into memory.
- [ ] Add structured error states and cancellation/progress support.
- [ ] Add test fixtures for clean files, metadata-rich files, malformed files and large files.
- [ ] Establish a capability registry so UI only offers actions supported by the selected file.
- [ ] Add regression tests and document supported formats and known limitations.

**Acceptance:** Existing app behavior remains stable; format support and action availability are truthful; malformed input cannot crash the app.

### Phase 1 — Deep Metadata Viewer (highest priority)
Build the strongest core analyzer first.

#### 1A. Universal file overview
- [ ] File name, extension, detected format, MIME type, file size and checksum/hash.
- [ ] Created/modified timestamps when available, with clear distinction between filesystem timestamps and embedded metadata timestamps.
- [ ] Image dimensions, orientation, colour model, bit depth, frame count and animation status where applicable.
- [ ] Audio/video duration, codecs, bitrate, frame rate, dimensions, channels and sample rate where available.
- [ ] PDF page count, page size, encryption status and document properties.
- [ ] Show the parser/handler responsible for each result.

#### 1B. Metadata categories
- [ ] Descriptive metadata: title, subject, description, caption, keywords, tags, comments.
- [ ] Creator/ownership metadata: author, artist, creator, copyright, rights, publisher, organization.
- [ ] Technical metadata: camera, lens, exposure, aperture, shutter speed, ISO, focal length, flash, resolution, codecs and encoding settings.
- [ ] Location metadata: GPS latitude/longitude, altitude, direction and location names, when present. Treat as sensitive; do not expose in logs.
- [ ] Administrative metadata: software, device, editing application, dates, identifiers and revision details.
- [ ] Structural metadata: tracks, streams, pages, layers, frames, embedded objects and container structure.
- [ ] Preservation/provenance metadata: C2PA, XMP history, digital source type, signatures and hashes.
- [ ] Unknown/custom/private metadata blocks: show block name/type and size even when the value cannot be decoded.

#### 1C. Image metadata formats
- [ ] JPEG: EXIF, XMP, IPTC/Photoshop APP13, ICC APP2, comments, APP segments and supported C2PA/JUMBF APP11 structures.
- [ ] PNG: eXIf, tEXt, zTXt, iTXt, tIME, caBX/JUMBF, ICC and colour chunks.
- [ ] WebP: EXIF, XMP, ICCP and RIFF chunk structure.
- [ ] TIFF: IFDs, EXIF, GPS, IPTC, XMP, ICC and nested directories.
- [ ] HEIF/HEIC and AVIF: item properties, EXIF, XMP, ICC and supported C2PA/JUMBF structures.
- [ ] GIF: comments, application extensions, animation/frame properties and loop data.
- [ ] BMP: headers, colour tables, profiles and supported extension data.
- [ ] SVG: XML metadata, RDF, XMP, creator fields and embedded metadata. Treat SVG as active/untrusted content; do not execute scripts.
- [ ] Camera RAW formats: identify format and show supported metadata where safe; clearly label unsupported maker notes.

#### 1D. Other file families
- [ ] PDF: document info dictionary, XMP, dates, author, producer, creator, page geometry, embedded files, fonts, images, annotations and signatures.
- [ ] Office: DOC/DOCX, XLS/XLSX, PPT/PPTX author, company, title, dates, comments, custom properties and package metadata.
- [ ] OpenDocument: ODT/ODS/ODP document properties and package metadata.
- [ ] Audio: MP3 ID3, WAV INFO/BWF, FLAC Vorbis comments, M4A/MP4 atoms, OGG comments and supported tags.
- [ ] Video: MP4/MOV atoms, MKV/Matroska tags, AVI headers, WebM tags, codec/stream details, subtitles and container metadata.
- [ ] EPUB and other ebook formats: title, author, publisher, language, identifiers, dates and package metadata.
- [ ] Unknown file types: show safe file signature/format identification and generic properties without pretending to parse internal metadata.

#### 1E. AI metadata and Content Credentials
- [ ] Detect AI-related metadata when explicitly present in EXIF, XMP, PNG text, application chunks or C2PA/JUMBF.
- [ ] Display generator/software name, model, service, prompt or workflow fields when present and safe to display.
- [ ] Display C2PA digital source type, claim generator, actions, timestamps, ingredient references, assertion labels and binary block sizes.
- [ ] Clearly distinguish an explicit AI/provenance declaration from an AI-generated guess.
- [ ] Do not infer that an image is AI-generated merely because metadata is missing or a software name appears.
- [ ] Label C2PA parsing as “read, not cryptographically verified” until real signature/trust-chain validation is implemented.
- [ ] Never expose raw certificate/signature bytes in the normal UI; provide bounded technical details only.

#### 1F. Viewer experience
- [ ] Summary dashboard with total findings and category counts.
- [ ] Expand/collapse categories and nested structures.
- [ ] Search/filter by field name or value.
- [ ] Copy a value, copy a category, and export a full report.
- [ ] Export report to PDF and JSON; include parser version, timestamp and limitations.
- [ ] Distinguish raw value, formatted value, source block and decode status.
- [ ] Add “not present” only when a reliable parser checked the relevant field; otherwise show “not checked/unsupported”.

**Acceptance:** A metadata-rich fixture produces a complete, traceable report for every supported handler. Every displayed field identifies its source or parser. Tests cover nested values, binary values, malformed containers and limits.

### Phase 2 — Metadata Remover / Clean All
- [ ] Build a per-format removal policy table.
- [ ] Offer “Clean All” and, where safe, selective category removal.
- [ ] Show a preview of detected fields/blocks that will be removed.
- [ ] Explain irreversible removal of GPS, author details, editing history and C2PA provenance.
- [ ] JPEG: remove supported EXIF, XMP, IPTC/Photoshop, comments and metadata APP segments; preserve required image markers and ICC/Adobe data by default.
- [ ] PNG: remove supported EXIF, textual, time and C2PA chunks; preserve image data and required colour/profile chunks.
- [ ] WebP: remove supported EXIF/XMP chunks; rebuild RIFF sizes and padding correctly; preserve image and ICC data.
- [ ] Add dedicated safe handlers for TIFF, HEIC/AVIF, GIF, BMP, SVG and RAW only after format-specific tests exist.
- [ ] Add PDF metadata removal without corrupting page content, signatures or encryption silently.
- [ ] Add Office/OpenDocument package property removal without breaking ZIP/XML package structure.
- [ ] Add audio/video tag and container metadata removal without re-encoding streams where feasible.
- [ ] Never promise removal of invisible watermarks, steganographic data, unknown proprietary payloads or all C2PA remnants without dedicated verified support.
- [ ] Save as a new output file and retain the original.
- [ ] Rescan the output using the same scanner.
- [ ] Produce a before/after report: found, removed, preserved, remaining, unsupported and errors.
- [ ] Verify output opens/decodes and media quality/content remains intact.

**Acceptance:** Every cleaner has fixtures proving the targeted fields are removed, protected content remains valid, and the rescan report accurately identifies remaining metadata. No fake success count.

### Phase 3 — Edit Metadata
- [ ] Build an editable field model with type validation and format-specific constraints.
- [ ] Edit supported fields such as title, author, copyright, description, keywords, dates and GPS where format permits.
- [ ] Delete selected fields without changing unrelated metadata.
- [ ] Provide reset/undo before saving.
- [ ] Preview changes and save a new copy.
- [ ] Validate dates, coordinates, numeric ranges, text encoding and field length.
- [ ] Preserve unsupported fields and clearly label them read-only.
- [ ] Rescan saved output and show the actual stored values.

**Acceptance:** Edited values survive reopening with an independent parser; untouched fields remain unchanged; invalid input is rejected safely.

### Phase 4 — Analyze Image
This is content analysis, not metadata inspection.
- [ ] Image description/caption generation.
- [ ] Explicit-content/safety classification with clear levels and uncertainty.
- [ ] People presence detection and approximate age-range estimation only if legally and technically appropriate; do not identify people.
- [ ] Batch analysis and per-image result states.
- [ ] Clearly state that content analysis does not prove whether an image is AI-generated.
- [ ] Prefer on-device inference. If a remote AI service is added, obtain explicit consent and explain what image data is sent.
- [ ] Provide delete/clear history controls for analysis results.

**Acceptance:** Results are clearly marked as estimates, privacy behavior is disclosed, and the app never presents a guess as fact.

### Phase 5 — PDF Parser
- [ ] Extract selectable text with page ordering.
- [ ] Extract embedded raster images in their original encoded form when possible (no unnecessary re-encoding).
- [ ] Extract embedded fonts where legally/technically permitted.
- [ ] Show a preview/list of pages, images and fonts before extraction.
- [ ] Export selected or all assets into a ZIP.
- [ ] Handle encrypted, damaged, scanned and very large PDFs with clear states.
- [ ] Do not bypass PDF passwords or document access controls.
- [ ] Preserve source PDF; extraction never modifies it.

**Acceptance:** Extracted text follows page order; extracted assets open correctly; ZIP contents and counts are verified.

### Phase 6 — Extract Video Streams
- [ ] Inspect all tracks in supported containers.
- [ ] Extract video-only, audio-only and subtitle-only streams.
- [ ] Allow selection of individual tracks and output destination.
- [ ] Preserve original stream data without re-encoding whenever possible.
- [ ] Show codec, language, bitrate, resolution, frame rate, duration and track ID.
- [ ] Handle unsupported codecs, DRM and damaged containers with honest messages.
- [ ] Provide progress, cancellation and output verification.

**Acceptance:** Extracted tracks are playable/readable and stream-copy paths do not re-encode.

### Phase 7 — PDF Asset Remover
- [ ] Remove all text objects, embedded images or vector graphics as separate user-selectable operations.
- [ ] Offer remove-all-assets only after preview and confirmation.
- [ ] Preserve page count, page order, page dimensions and page boxes where possible.
- [ ] Remove actual PDF objects/resources, not just draw white rectangles over content.
- [ ] Warn about signatures, forms, links, annotations, layers and accessibility data that may be affected.
- [ ] Save a new PDF and verify it opens and the targeted objects are absent.

**Acceptance:** Tests inspect the resulting PDF object structure and confirm that targeted objects were actually removed.

### Phase 8 — Compare Images
- [ ] Select two images and show dimensions, format, file size and colour-profile differences.
- [ ] Side-by-side and synchronized zoom/pan.
- [ ] Opacity overlay and difference heatmap.
- [ ] Threshold control and RGB channel comparison.
- [ ] Add supported metrics as separately named calculations: SSIM, NCC, PSNR, LPIPS, AE, MAE, RMSE, PDC and Butteraugli.
- [ ] Explain metric meaning, scale, limitations and whether higher/lower is better.
- [ ] Generate a shareable PDF report.
- [ ] Do not compare incompatible dimensions without an explicit, disclosed normalization step.

**Acceptance:** Metric implementations are tested against known fixtures/reference values; reports state all preprocessing performed.

### Phase 9 — Compare Videos
- [ ] Compare video properties and stream layouts.
- [ ] Frame-by-frame comparison and synchronized playback.
- [ ] Frame difference gallery and timeline navigation.
- [ ] Support applicable quality metrics such as VMAF, PSNR, SSIM, VIF and ADM only when dependencies and licensing permit.
- [ ] Show frame alignment, scaling, frame-rate conversion and other preprocessing.
- [ ] Generate a PDF report with per-video properties, metrics and representative frames.
- [ ] Handle different durations/codecs and unsupported inputs clearly.

**Acceptance:** Comparisons are reproducible; alignment and preprocessing are disclosed; metrics are not fabricated when unavailable.

### Phase 10 — Compare PDFs
- [ ] Compare page counts, dimensions and document properties.
- [ ] Detect added, removed and reordered pages.
- [ ] Page-by-page visual difference view.
- [ ] Side-by-side, overlay/slider and difference-highlight modes.
- [ ] Text-level differences where extraction is reliable.
- [ ] Provide page navigation and a summary of changed pages.
- [ ] Export a PDF comparison report.
- [ ] Prefer local processing; do not upload documents by default.

**Acceptance:** Test PDFs cover inserted/deleted pages, text edits, image changes, rotation and layout changes.

### Phase 11 — Batch processing and workflow polish
- [ ] Multi-select files and folders through SAF.
- [ ] Batch scan, clean, edit and export where the format handler supports it.
- [ ] Queue with per-file progress, pause/cancel and retry.
- [ ] Show per-file success/failure and output locations.
- [ ] Avoid loading every file into memory simultaneously.
- [ ] Add task presets only for operations whose behavior is fully defined.
- [ ] Add optional notifications for long-running tasks.
- [ ] Add exportable processing history with privacy-conscious defaults.

**Acceptance:** Mixed-format batches never report unsupported files as successful; cancellation and partial failure are handled correctly.

### Phase 12 — Premium UI, onboarding and release quality
- [ ] Home screen with clear tool categories and recent files/tasks.
- [ ] File picker with image, video, audio, PDF, document and other categories.
- [ ] File details screen with preview, overview, metadata and available actions.
- [ ] Consistent design system: typography, spacing, colour, cards, icons and buttons.
- [ ] Dark/light themes with readable contrast.
- [ ] Premium restrained gradient/neon accents, not at the cost of legibility.
- [ ] Permission onboarding explains SAF and optional broad access.
- [ ] About screen explains supported formats, privacy and limitations.
- [ ] Empty, loading, error, no-metadata and unsupported-format states.
- [ ] Accessibility, TalkBack, large font and touch-target checks.
- [ ] Test on different Android versions, screen sizes and storage providers.
- [ ] Release build, crash checks, performance checks and final privacy review.

**Acceptance:** No clipped/hidden text, unreadable contrast, dead buttons, misleading permission prompts or false success states.

## 6. Supported-format truth table

Maintain a real in-app or documentation table with these columns:
- Format/extension
- Detection support
- Metadata read support
- Edit support
- Clean support
- Content/stream extraction support
- Known limitations
- Test fixture/reference

Never infer support for a whole family from one extension. For example, MP4 support does not automatically mean MOV/M4V/3GP support.

## 7. Metadata report data model

Each finding should aim to include:
- `id`: stable identifier within the report
- `category`: descriptive, technical, administrative, structural, location, provenance, unknown
- `name`: readable field name
- `path`: nested path/key
- `value`: decoded value, if safe and available
- `formattedValue`: display-friendly representation
- `dataType`: text, integer, decimal, boolean, date, binary, array, object, unknown
- `sourceFormat`: EXIF, XMP, IPTC, PNG chunk, JPEG APP segment, MP4 atom, PDF object, etc.
- `sourceBlock`: exact block/chunk/segment name when known
- `byteSize`: payload size when known
- `status`: decoded, binary, malformed, unsupported, truncated
- `sensitive`: whether the value may reveal personal information

Do not force unavailable information into fake/default values.

## 8. Required test strategy

- Unit tests for each parser and cleaner independently.
- Golden fixtures with known metadata and expected reports.
- Before/after tests for each removal policy.
- Round-trip/openability tests for cleaned or edited files.
- Malformed length, truncated data, invalid offsets, oversized counts and recursion-limit tests.
- Unicode, unusual encodings, empty values, duplicate tags and unknown chunks.
- Regression tests proving ICC profiles, image pixels, audio/video streams or PDF pages are preserved where promised.
- Independent-tool verification for representative outputs where practical (for example ExifTool, MediaInfo, PDF inspection tools).
- Android instrumentation tests for SAF, permission denial, file access and output saving.
- Build and test on CI. A successful compile alone is not proof of correct metadata behavior.

## 9. Definition of done for every task

A task is complete only when:
1. The requested behavior is implemented, not merely represented in the UI.
2. Supported and unsupported cases are explicit.
3. Relevant unit/integration tests are added or updated and pass.
4. The relevant Android build passes when applicable.
5. Output files are validated and originals remain safe.
6. The UI reports real results and handles errors/permissions.
7. The diff contains no unrelated changes or secrets.
8. Documentation/status is updated.
9. The final response lists exact changes, tests run, results and limitations.

## 10. Agent execution instruction

When asked to continue this roadmap:
1. Read this file.
2. Check off the earliest incomplete phase/subtask that is feasible and relevant.
3. Inspect only the required source and test files.
4. Implement that bounded task end-to-end.
5. Test it and update this checklist only for verified work.
6. Stop and report clearly. Do not silently jump to unrelated future phases.

If a task is too large, split it into smaller PR-sized steps and begin with the highest-priority safe step. Do not replace working architecture with a large speculative rewrite.
