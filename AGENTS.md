# Instructions for coding agents

Before making changes, read:
1. docs/METAVAULT_MASTER_PROMPT.md — product requirements, architecture, privacy rules, acceptance criteria, current baseline, and priorities.
2. README.md — repository overview and current limitations.
3. android/README.md — Android setup and current Android MVP.

## Working rules
- The master brief is the target specification, not evidence that every feature exists.
- Inspect actual code and latest CI result before deciding what is complete.
- Work in small, buildable increments. Add tests for new behavior.
- Never fake metadata cleaning, success states, file counts, or supported formats.
- Keep processing local; do not add uploads or unnecessary telemetry.
- Preserve originals by default. Never overwrite without explicit confirmation and a safe recovery path.
- Prefer SAF/MediaStore and least-privilege permissions. All-files access must remain optional.
- Update README/support documentation whenever capabilities change.
- Run Android build and relevant tests; report exact outcome and remaining limitations.
- Do not commit secrets, signing keys, user files, or generated private media.

## Definition of done
A feature is done only when implementation, tests, UI behavior, output verification, and documentation agree. Be precise about unsupported metadata and formats.
