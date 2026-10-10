---
id: commit-message
version: 1
used for: drafting commit subjects; the body's "why" is always written by us
model and settings: Claude Code
acceptance check: subject in the imperative, at most 72 characters; body says why, not only what
---

<task>Draft a commit subject (imperative mood, at most 72 characters, no type prefix) for the
staged diff, then a body of two or three sentences stating what changed. Leave a line `WHY:`
empty for us to fill in.</task>

<diff>{{git diff --staged}}</diff>

<constraints>Do not guess the motivation. Do not mention files that did not change.</constraints>

## Changelog
- v1 (10 Oct): first version. The empty `WHY:` exists because the model sees what changed, never
  why (Module 3: "the why is yours").
