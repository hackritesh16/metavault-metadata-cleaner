# MetaVault — Metadata Cleaner

A privacy-first, glassmorphism-style metadata-cleaning web app.

## Current status

**Starter MVP — image workflow implemented.** The interface includes file-type selection for images, video, audio, PDF and other files. At this stage, only browser-side image cleaning is implemented; the other format adapters are clearly marked as upcoming.

## Features
- Premium responsive glass / haze interface
- Local file selection and drag-and-drop
- Preserve-quality preference and output format selector
- Browser-side image decode/re-encode to strip embedded metadata
- Download cleaned image and processing report
- No file upload or server required for the current image workflow

## Run locally

Open `index.html` in a modern browser, or serve this folder with any static web server.

## Important quality note

Browser canvas re-encoding preserves image dimensions, but it is not byte-for-byte lossless. JPEG and WebP may be re-encoded and can change image data or file size. The UI reports this honestly. A true lossless metadata-strip path and robust video/audio/PDF/document adapters are future milestones.

## Roadmap
- [ ] Lossless JPEG metadata removal
- [ ] Video remuxing without re-encoding (FFmpeg/WebCodecs evaluation)
- [ ] Audio tag removal with format-aware handling
- [ ] PDF metadata sanitization
- [ ] Office document metadata cleaning
- [ ] Automated tests and security review

## Privacy

The current image-processing path runs locally in the user's browser. No file is uploaded.
