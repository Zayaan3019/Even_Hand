---
id: document-for-a-reader
version: 1
used for: Javadoc on public types; docs/ARCHITECTURE.md and docs/CODE_MAP.md
model and settings: Claude Code
acceptance check: a teammate who did not write the class can say what it does, what it refuses and how it is tested, from the doc alone
---

<audience>{{one of: the instructor running the tool | a teammate in the viva | an examiner asking for a live change}}</audience>

<task>Write the documentation for `{{class or module}}` for that reader.</task>

<constraints>
- Lead with what it does in one sentence, in the reader's words (marks, TAs, grades), not ours.
- Then: inputs and outputs, what it refuses and why, the test that pins its behaviour.
- No marketing words. No claim the code does not enforce.
- Javadoc explains *why* where the code cannot; it does not restate the signature.
</constraints>

## Changelog
- v1 (10 Oct): first version.
