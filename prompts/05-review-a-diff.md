---
id: review-a-diff
version: 1
used for: every non-trivial commit before it is made
model and settings: Claude Code; review
acceptance check: every BLOCKER is fixed, or answered in the commit body
---

<task>Review the diff below as the module's owner would. Report findings as BLOCKER / SHOULD / NIT,
each with the line and a one-sentence reason. Do not rewrite the code.</task>

<invariants>
1. No roll number or TA name is written anywhere except the sealed key file.
2. No class uses the network.
3. Every figure shown to an instructor carries an interval.
4. Refusal is a value with a reason, never an exception or an empty list.
5. Dependencies flow ingest -> design -> estimate -> outcome -> report -> app, never back.
6. Every random draw is seeded.
</invariants>

<diff>{{git diff --staged}}</diff>

Restating: findings only, ranked by severity, invariants first.

## Changelog
- v1 (10 Oct): first version.
