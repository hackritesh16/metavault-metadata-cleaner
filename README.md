# MetaVault — Metadata Cleaner

A privacy-first, responsive glassmorphism workspace for cleaning file metadata.

## Current release

**Image-cleaning MVP (JPG, PNG and WEBP).** The app runs entirely in the browser for the supported image workflow. Files are never sent to a server.

## Features
- Premium dark glass / haze UI with responsive mobile layout
- File-type selector for images, video, audio, PDF and other documents
- Drag-and-drop and local file picker
- Image preview, file details and processing progress
- Preserve-format / best-effort quality preference
- Optional PNG, JPG or WEBP output conversion
- Downloadable clean image copy and processing report
- Original file is never modified

## Run locally

Open `index.html` in a modern browser, or serve the folder with any static web server.

## Important quality and privacy notes

The current image path decodes and re-encodes the image using the browser canvas. It preserves pixel dimensions and removes embedded image metadata, but it is **not byte-for-byte lossless**. JPEG/WebP pixels may change slightly and file size may increase or decrease. When converting transparent PNG/WebP to JPG, transparent areas are composited onto white.

The UI includes video, audio, PDF and other-document selectors as roadmap placeholders. Those formats are **not yet processed**. The app clearly tells users this instead of pretending to clean files.

## Roadmap
- [ ] Lossless JPEG metadata removal
- [ ] Video metadata removal via safe remuxing (FFmpeg/WebCodecs evaluation)
- [ ] Audio tag/chunk removal with format-aware validation
- [ ] PDF metadata sanitization and verification
- [ ] Office document metadata cleaning
- [ ] Automated tests, large-file safeguards and security review

## Security principles
- No upload endpoint or third-party file transfer
- Never overwrite the user's source file
- Report only operations actually performed
- Validate file type and output format before processing
